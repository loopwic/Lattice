use anyhow::Result;
use axum::http::header::AUTHORIZATION;
use backend_application::commands::op_token_commands;
use backend_application::{AppError, AppState};
use backend_domain::OpTokenIssueRequest;
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use std::time::Duration;
use tokio::time::sleep;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::Message;
use tracing::{info, warn};

const RECONNECT_DELAY_SECONDS: u64 = 5;

pub fn spawn_napcat_ws_bridge(state: AppState) {
    let ws_urls = resolve_ws_source_urls(&state.config);
    if ws_urls.is_empty() {
        info!("napcat ws bridge disabled: no ws webhook url configured");
        return;
    }
    let ws_token = state.config.alert_webhook_token.clone();

    for ws_url in ws_urls {
        let loop_state = state.clone();
        let loop_token = ws_token.clone();
        tokio::spawn(async move {
            loop {
                match connect_ws(&ws_url, loop_token.as_deref()).await {
                    Ok((mut ws, mode)) => {
                        info!("napcat ws bridge connected: url={}, mode={}", ws_url, mode);
                        if let Err(err) = run_bridge_loop(&loop_state, &mut ws).await {
                            warn!("napcat ws bridge loop exited: url={}, err={}", ws_url, err);
                        }
                    }
                    Err(err) => {
                        warn!("napcat ws bridge connect failed: url={}, err={}", ws_url, err);
                    }
                }
                sleep(Duration::from_secs(RECONNECT_DELAY_SECONDS)).await;
            }
        });
    }
}

async fn connect_ws(
    ws_url: &str,
    token: Option<&str>,
) -> Result<(
    tokio_tungstenite::WebSocketStream<
        tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
    >,
    &'static str,
)> {
    let mut request = ws_url.into_client_request()?;
    if let Some(value) = token.filter(|raw| !raw.trim().is_empty()) {
        request
            .headers_mut()
            .insert(AUTHORIZATION, format!("Bearer {}", value).parse()?);
    }

    if let Ok((socket, _)) = tokio_tungstenite::connect_async(request).await {
        return Ok((socket, "header"));
    }

    let with_query = add_access_token_query(ws_url, token);
    let query_request = with_query.into_client_request()?;
    if let Ok((socket, _)) = tokio_tungstenite::connect_async(query_request).await {
        return Ok((socket, "query"));
    }

    let plain_request = ws_url.into_client_request()?;
    let (socket, _) = tokio_tungstenite::connect_async(plain_request).await?;
    Ok((socket, "plain"))
}

async fn run_bridge_loop(
    state: &AppState,
    ws: &mut tokio_tungstenite::WebSocketStream<
        tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
    >,
) -> Result<()> {
    while let Some(next) = ws.next().await {
        match next {
            Ok(Message::Text(text)) => {
                let Some(event) = parse_group_message_event(text.as_ref()) else {
                    continue;
                };
                if !is_issue_token_command(&event.command_text) {
                    continue;
                }

                let request = OpTokenIssueRequest {
                    server_id: None,
                    operator_id: event.user_id.map(|value| value.to_string()),
                    group_id: Some(event.group_id.to_string()),
                };
                let reply = match op_token_commands::issue_op_token(state, request).await {
                    Ok(issued) => format!(
                        "OP token 已签发（当天有效）\\n{}\\n过期时间: {}\\n游戏内使用: /lattice token apply <token>",
                        issued.token, issued.expires_at
                    ),
                    Err(err) => map_issue_error_message(&err),
                };

                let action_echo = format!(
                    "lattice-auto-{}",
                    chrono::Utc::now().timestamp_millis()
                );
                let action = json!({
                    "action": "send_group_msg",
                    "params": {
                        "group_id": event.group_id,
                        "message": reply,
                    },
                    "echo": action_echo,
                })
                .to_string();
                ws.send(Message::Text(action.into())).await?;
            }
            Ok(Message::Ping(bytes)) => {
                ws.send(Message::Pong(bytes)).await?;
            }
            Ok(Message::Close(frame)) => {
                return Err(anyhow::anyhow!("ws closed by peer: {:?}", frame));
            }
            Ok(_) => {}
            Err(err) => {
                return Err(anyhow::anyhow!("ws stream error: {}", err));
            }
        }
    }
    Err(anyhow::anyhow!("ws stream ended"))
}

fn resolve_ws_source_urls(config: &backend_domain::RuntimeConfig) -> Vec<String> {
    let mut urls = Vec::new();
    if let Some(alert_url) = config
        .alert_webhook_url
        .as_deref()
        .map(str::trim)
        .filter(|value| value.starts_with("ws://") || value.starts_with("wss://"))
    {
        urls.push(alert_url.to_string());
    }
    if let Some(webhook_url) = config
        .webhook_url
        .as_deref()
        .map(str::trim)
        .filter(|value| value.starts_with("ws://") || value.starts_with("wss://"))
    {
        if !urls.iter().any(|item| item == webhook_url) {
            urls.push(webhook_url.to_string());
        }
    }
    urls
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

fn map_issue_error_message(err: &AppError) -> String {
    match err {
        AppError::Unauthorized => "申请失败：当前群未授权申请 OP token".to_string(),
        AppError::BadRequest(message) => format!("申请失败：{}", message),
        AppError::Internal(_) => "申请失败：后端内部错误".to_string(),
    }
}

fn is_issue_token_command(text: &str) -> bool {
    matches!(text.trim(), "/申请" | "申请" | "/申请token" | "申请token")
}

fn parse_group_message_event(raw_text: &str) -> Option<GroupMessageEvent> {
    let value: Value = serde_json::from_str(raw_text).ok()?;
    let post_type = value.get("post_type").and_then(Value::as_str).unwrap_or("");
    let message_type = value
        .get("message_type")
        .and_then(Value::as_str)
        .unwrap_or("");
    if !post_type.eq_ignore_ascii_case("message") || !message_type.eq_ignore_ascii_case("group") {
        return None;
    }

    let group_id = value.get("group_id").and_then(parse_i64)?;
    if group_id <= 0 {
        return None;
    }
    let user_id = value.get("user_id").and_then(parse_i64).filter(|id| *id > 0);

    let raw_message = value
        .get("raw_message")
        .and_then(Value::as_str)
        .unwrap_or("");
    let message_segments = value.get("message");
    let command_text = normalize_command_text(raw_message, message_segments);
    if command_text.is_empty() {
        return None;
    }

    Some(GroupMessageEvent {
        group_id,
        user_id,
        command_text,
    })
}

fn parse_i64(value: &Value) -> Option<i64> {
    match value {
        Value::Number(number) => number.as_i64(),
        Value::String(text) => text.trim().parse::<i64>().ok(),
        _ => None,
    }
}

fn normalize_command_text(raw_message: &str, segments: Option<&Value>) -> String {
    let mut text = if raw_message.trim().is_empty() {
        extract_text_segments(segments)
    } else {
        raw_message.trim().to_string()
    };
    if text.is_empty() {
        return text;
    }

    text = text.replace('／', "/");
    text.split_whitespace()
        .filter(|part| {
            let lower = part.to_ascii_lowercase();
            !(lower.starts_with("[cq:at,qq=") && lower.ends_with(']'))
        })
        .collect::<Vec<_>>()
        .join(" ")
        .trim()
        .to_string()
}

fn extract_text_segments(segments: Option<&Value>) -> String {
    let Some(Value::Array(items)) = segments else {
        return String::new();
    };
    let mut out = String::new();
    for item in items {
        let Some(obj) = item.as_object() else {
            continue;
        };
        if obj.get("type").and_then(Value::as_str) != Some("text") {
            continue;
        }
        let text = obj
            .get("data")
            .and_then(Value::as_object)
            .and_then(|data| data.get("text"))
            .and_then(Value::as_str)
            .unwrap_or("");
        out.push_str(text);
    }
    out
}

struct GroupMessageEvent {
    group_id: i64,
    user_id: Option<i64>,
    command_text: String,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn parse_group_message_command_from_raw() {
        let event = parse_group_message_event(
            r#"{"post_type":"message","message_type":"group","group_id":616632545,"user_id":2295657647,"raw_message":"/申请token"}"#,
        )
        .expect("event");
        assert_eq!(event.group_id, 616632545);
        assert_eq!(event.user_id, Some(2295657647));
        assert_eq!(event.command_text, "/申请token");
    }

    #[test]
    fn parse_group_message_command_from_segments() {
        let payload = json!({
            "post_type":"message",
            "message_type":"group",
            "group_id":"616632545",
            "message":[
                {"type":"at","data":{"qq":"123456"}},
                {"type":"text","data":{"text":" /申请 "}}
            ]
        });
        let event = parse_group_message_event(&payload.to_string()).expect("event");
        assert_eq!(event.command_text, "/申请");
    }

    #[test]
    fn command_match_supports_aliases() {
        assert!(is_issue_token_command("/申请"));
        assert!(is_issue_token_command("申请token"));
        assert!(!is_issue_token_command("/无关命令"));
    }
}
