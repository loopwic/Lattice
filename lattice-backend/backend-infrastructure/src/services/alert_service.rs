use std::time::Duration;

use anyhow::Result;
use async_trait::async_trait;
use futures_util::{SinkExt, StreamExt};
use reqwest::header::AUTHORIZATION;
use reqwest::Client;
use serde_json::json;
use tokio::time::timeout;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::Message;
use tracing::warn;

use backend_domain::ports::AlertService;
use backend_domain::{AnomalyRow, RuntimeConfig};

#[derive(Default)]
pub struct DefaultAlertService;

impl DefaultAlertService {
    pub fn new() -> Self {
        Self
    }
}

#[async_trait]
impl AlertService for DefaultAlertService {
    fn spawn_alerts(&self, config: RuntimeConfig, anomalies: Vec<AnomalyRow>) {
        let alerts = anomalies
            .into_iter()
            .filter(|row| row.rule_id == "R4")
            .collect::<Vec<_>>();
        if alerts.is_empty() {
            return;
        }
        tokio::spawn(async move {
            if let Err(err) = send_alerts(&config, &alerts).await {
                warn!("alert webhook failed: {}", err);
            }
        });
    }

    async fn check_alert_target(&self, config: &RuntimeConfig) -> Result<()> {
        check_alert_target(config).await
    }
}

pub async fn check_alert_target(config: &RuntimeConfig) -> Result<()> {
    let url = resolve_alert_url(config)?;
    if url.starts_with("ws://") || url.starts_with("wss://") {
        check_ws_target(config, &url).await
    } else {
        check_http_target(config, &url).await
    }
}

async fn send_alerts(config: &RuntimeConfig, alerts: &[AnomalyRow]) -> Result<()> {
    let url = resolve_alert_url(config)?;
    if url.starts_with("ws://") || url.starts_with("wss://") {
        send_ws_alerts(config, &url, alerts).await
    } else {
        send_http_alerts(config, &url, alerts).await
    }
}

async fn send_http_alerts(config: &RuntimeConfig, url: &str, alerts: &[AnomalyRow]) -> Result<()> {
    let template = config
        .alert_webhook_template
        .as_deref()
        .unwrap_or(r#"{"message":"稀有物资告警 {total} 条\n{lines}"}"#);

    let payload = build_payload(alerts, template);
    let client = Client::builder()
        .timeout(Duration::from_secs(config.request_timeout_seconds.max(3)))
        .build()?;

    client
        .post(url)
        .header("Content-Type", "application/json")
        .body(payload)
        .send()
        .await?
        .error_for_status()?;
    Ok(())
}

async fn check_http_target(config: &RuntimeConfig, url: &str) -> Result<()> {
    let client = Client::builder()
        .timeout(Duration::from_secs(config.request_timeout_seconds.max(3)))
        .build()?;
    let response = client.get(url).send().await?;
    if !response.status().is_success() {
        anyhow::bail!("alert webhook responded {}", response.status());
    }
    Ok(())
}

async fn check_ws_target(config: &RuntimeConfig, url: &str) -> Result<()> {
    let token = config.alert_webhook_token.clone();
    if let Err(err) = try_ws_check(url, token.as_deref(), false).await {
        if token.as_ref().is_some() {
            return try_ws_check(url, token.as_deref(), true).await;
        }
        return Err(err);
    }
    Ok(())
}

async fn send_ws_alerts(config: &RuntimeConfig, url: &str, alerts: &[AnomalyRow]) -> Result<()> {
    let group_id = config
        .alert_group_id
        .ok_or_else(|| anyhow::anyhow!("alert_group_id not configured"))?;
    let message = build_message(alerts);
    let payload = json!({
        "action": "send_group_msg",
        "params": {
            "group_id": group_id,
            "message": message,
        },
        "echo": format!("lattice-{}", chrono::Utc::now().timestamp_millis()),
    })
    .to_string();

    let token = config.alert_webhook_token.clone();
    if let Err(err) = try_ws_send(url, token.as_deref(), &payload, false).await {
        if token.as_ref().is_some() {
            let _ = try_ws_send(url, token.as_deref(), &payload, true).await?;
        } else {
            return Err(err);
        }
    }
    Ok(())
}

async fn try_ws_check(url: &str, token: Option<&str>, use_query: bool) -> Result<()> {
    let mut request = if use_query {
        add_access_token_query(url, token).into_client_request()?
    } else {
        url.into_client_request()?
    };

    if let Some(token) = token {
        if !use_query {
            request
                .headers_mut()
                .insert(AUTHORIZATION, format!("Bearer {}", token).parse()?);
        }
    }

    let (mut ws, _) = tokio_tungstenite::connect_async(request).await?;
    let payload = json!({
        "action": "get_status",
        "params": {},
        "echo": format!("lattice-check-{}", chrono::Utc::now().timestamp_millis()),
    })
    .to_string();
    ws.send(Message::Text(payload)).await?;
    let _ = timeout(Duration::from_secs(2), ws.next()).await?;
    let _ = ws.close(None).await;
    Ok(())
}

async fn try_ws_send(url: &str, token: Option<&str>, payload: &str, use_query: bool) -> Result<()> {
    let mut request = if use_query {
        add_access_token_query(url, token).into_client_request()?
    } else {
        url.into_client_request()?
    };

    if let Some(token) = token {
        if !use_query {
            request
                .headers_mut()
                .insert(AUTHORIZATION, format!("Bearer {}", token).parse()?);
        }
    }

    let (mut ws, _) = tokio_tungstenite::connect_async(request).await?;
    ws.send(Message::Text(payload.to_string())).await?;
    let _ = timeout(Duration::from_secs(2), ws.next()).await.ok();
    let _ = ws.close(None).await;
    Ok(())
}

fn add_access_token_query(url: &str, token: Option<&str>) -> String {
    let token = match token {
        Some(value) if !value.trim().is_empty() => value,
        _ => return url.to_string(),
    };
    if url.contains("access_token=") {
        return url.to_string();
    }
    if url.contains('?') {
        format!("{}&access_token={}", url, token)
    } else {
        format!("{}?access_token={}", url, token)
    }
}

fn resolve_alert_url(config: &RuntimeConfig) -> Result<String> {
    if let Some(url) = &config.alert_webhook_url {
        if !url.trim().is_empty() {
            return Ok(url.clone());
        }
    }
    if let Some(url) = &config.webhook_url {
        if !url.trim().is_empty() {
            return Ok(url.clone());
        }
    }
    anyhow::bail!("alert webhook url not configured")
}

fn build_message(alerts: &[AnomalyRow]) -> String {
    let mut lines = Vec::new();
    lines.push(format!("稀有物资告警 {} 条", alerts.len()));
    for row in alerts.iter().take(8) {
        lines.push(format!(
            "{} | {} x{} | {}",
            row.player_name, row.item_id, row.count, row.risk_level
        ));
    }
    if alerts.len() > 8 {
        lines.push(format!("...还有 {} 条未展示", alerts.len() - 8));
    }
    lines.join("\n")
}

fn build_payload(alerts: &[AnomalyRow], template: &str) -> String {
    let lines = alerts
        .iter()
        .take(8)
        .map(|row| format!("{} | {} x{} | {}", row.player_name, row.item_id, row.count, row.risk_level))
        .collect::<Vec<_>>();
    let mut line_text = lines.join("\\n");
    if alerts.len() > 8 {
        line_text.push_str(&format!("\\n...还有 {} 条未展示", alerts.len() - 8));
    }
    template
        .replace("{total}", &alerts.len().to_string())
        .replace("{lines}", &line_text)
}
