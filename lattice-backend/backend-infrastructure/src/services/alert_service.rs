use std::collections::{BTreeSet, VecDeque};
use std::sync::Arc;
use std::time::Duration;

use anyhow::Result;
use async_trait::async_trait;
use futures_util::{SinkExt, StreamExt};
use reqwest::header::AUTHORIZATION;
use reqwest::Client;
use serde_json::json;
use tokio::sync::RwLock;
use tokio::time::{sleep, timeout};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::Message;
use tracing::warn;

use backend_domain::ports::AlertService;
use backend_domain::{AlertDeliveryRecord, AnomalyRow, RuntimeConfig};

const DELIVERY_HISTORY_LIMIT: usize = 200;
const ALERT_RETRY_ATTEMPTS: u8 = 3;
const ALERT_RETRY_BASE_MS: u64 = 400;

#[derive(Clone)]
pub struct DefaultAlertService {
    deliveries: Arc<RwLock<VecDeque<AlertDeliveryRecord>>>,
    history_limit: usize,
}

impl Default for DefaultAlertService {
    fn default() -> Self {
        Self::new()
    }
}

impl DefaultAlertService {
    pub fn new() -> Self {
        Self::with_history_limit(DELIVERY_HISTORY_LIMIT)
    }

    pub fn with_history_limit(history_limit: usize) -> Self {
        Self {
            deliveries: Arc::new(RwLock::new(VecDeque::new())),
            history_limit: history_limit.max(1),
        }
    }
}

#[async_trait]
impl AlertService for DefaultAlertService {
    fn spawn_alerts(&self, config: RuntimeConfig, anomalies: Vec<AnomalyRow>) {
        let alerts = anomalies
            .into_iter()
            .filter(|row| should_emit_alert(&row.rule_id))
            .collect::<Vec<_>>();
        if alerts.is_empty() {
            return;
        }

        let deliveries = self.deliveries.clone();
        let history_limit = self.history_limit;
        tokio::spawn(async move {
            let mode = resolve_alert_mode(&config);
            let (attempts, error) =
                send_alerts_with_retry(&config, &alerts, ALERT_RETRY_ATTEMPTS).await;
            let status = if error.is_none() {
                "success".to_string()
            } else {
                "failed".to_string()
            };

            let mut rule_ids = BTreeSet::new();
            for row in &alerts {
                rule_ids.insert(row.rule_id.clone());
            }

            let record = AlertDeliveryRecord {
                timestamp_ms: chrono::Utc::now().timestamp_millis(),
                status,
                mode,
                attempts,
                alert_count: alerts.len(),
                rule_ids: rule_ids.into_iter().collect(),
                error: error.clone(),
            };
            push_delivery(deliveries, history_limit, record).await;

            if let Some(err) = error {
                warn!("alert webhook failed after {attempts} attempts: {err}");
            }
        });
    }

    async fn check_alert_target(&self, config: &RuntimeConfig) -> Result<()> {
        check_alert_target(config).await
    }

    async fn list_alert_deliveries(&self, limit: usize) -> Vec<AlertDeliveryRecord> {
        let limit = limit.max(1).min(self.history_limit);
        let deliveries = self.deliveries.read().await;
        deliveries.iter().rev().take(limit).cloned().collect()
    }

    async fn last_alert_delivery(&self) -> Option<AlertDeliveryRecord> {
        self.deliveries.read().await.back().cloned()
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

fn should_emit_alert(rule_id: &str) -> bool {
    matches!(rule_id, "R4" | "R10" | "R12")
}

fn resolve_alert_mode(config: &RuntimeConfig) -> String {
    match resolve_alert_url(config) {
        Ok(url) if url.starts_with("ws://") || url.starts_with("wss://") => "ws".to_string(),
        Ok(_) => "http".to_string(),
        Err(_) => "unset".to_string(),
    }
}

async fn send_alerts_with_retry(
    config: &RuntimeConfig,
    alerts: &[AnomalyRow],
    retry_attempts: u8,
) -> (u8, Option<String>) {
    let attempts = retry_attempts.max(1);
    let mut current = 1u8;

    loop {
        match send_alerts(config, alerts).await {
            Ok(()) => return (current, None),
            Err(err) => {
                let message = err.to_string();
                if current >= attempts {
                    return (current, Some(message));
                }

                let backoff_ms = ALERT_RETRY_BASE_MS.saturating_mul(1u64 << (current - 1));
                sleep(Duration::from_millis(backoff_ms)).await;
                current += 1;
            }
        }
    }
}

async fn push_delivery(
    deliveries: Arc<RwLock<VecDeque<AlertDeliveryRecord>>>,
    history_limit: usize,
    record: AlertDeliveryRecord,
) {
    let mut guard = deliveries.write().await;
    guard.push_back(record);
    while guard.len() > history_limit.max(1) {
        guard.pop_front();
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
            try_ws_send(url, token.as_deref(), &payload, true).await?;
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
        .map(|row| {
            format!(
                "{} | {} x{} | {}",
                row.player_name, row.item_id, row.count, row.risk_level
            )
        })
        .collect::<Vec<_>>();
    let mut line_text = lines.join("\\n");
    if alerts.len() > 8 {
        line_text.push_str(&format!("\\n...还有 {} 条未展示", alerts.len() - 8));
    }
    template
        .replace("{total}", &alerts.len().to_string())
        .replace("{lines}", &line_text)
}
