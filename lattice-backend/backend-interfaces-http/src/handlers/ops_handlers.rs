use axum::extract::{
    ws::{Message, WebSocket, WebSocketUpgrade},
    Query, State,
};
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;
use futures_util::StreamExt;
use serde_json::Value;
use tokio::time::{timeout, Duration};
use tracing::{error, warn};

use backend_application::commands::{
    mod_config_commands, op_token_commands, task_progress_commands,
};
use backend_application::queries::{mod_config_queries, task_progress_queries};
use backend_application::{AppError, AppState};
use backend_domain::{
    AlertDeliveryRecord, ModConfigAck, ModConfigEnvelope, ModConfigPutRequest, OpTokenIssueRequest,
    OpTokenIssueResponse, OpTokenMisuseAlertRequest, RconConfig, TaskProgressUpdate, TaskStatus,
};

use crate::error::HttpError;
use crate::middleware::authorize;

#[derive(serde::Serialize)]
struct AlertStatus {
    status: String,
    mode: String,
}

#[derive(serde::Deserialize)]
pub struct AlertDeliveryQuery {
    pub limit: Option<usize>,
}

#[derive(serde::Deserialize)]
pub struct ServerIdQuery {
    pub server_id: Option<String>,
}

#[derive(serde::Deserialize)]
pub struct ModConfigPullQuery {
    pub server_id: Option<String>,
    pub after_revision: Option<u64>,
}

#[derive(serde::Deserialize, Debug)]
pub struct NapcatGroupMessageEvent {
    #[serde(default)]
    pub post_type: String,
    #[serde(default)]
    pub message_type: String,
    #[serde(default)]
    pub raw_message: Option<String>,
    #[serde(default)]
    pub group_id: Option<i64>,
    #[serde(default)]
    pub user_id: Option<i64>,
    #[serde(default)]
    pub message: Option<Value>,
}

pub async fn get_rcon_config(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<RconConfig>, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    let config = state
        .config_repo
        .load_rcon_config()
        .await
        .map_err(|err| HttpError::Internal(err.to_string()))?;
    Ok(Json(config))
}

pub async fn update_rcon_config(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<RconConfig>,
) -> Result<StatusCode, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    state
        .config_repo
        .save_rcon_config(&payload)
        .await
        .map_err(|err| HttpError::Internal(err.to_string()))?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn get_task_progress(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<TaskStatus>, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    let status = task_progress_queries::get_task_progress(&state).await;
    Ok(Json(status))
}

pub async fn update_task_progress(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<TaskProgressUpdate>,
) -> Result<StatusCode, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    task_progress_commands::update_task_progress(&state, payload).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn issue_op_token(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<OpTokenIssueRequest>,
) -> Result<Json<OpTokenIssueResponse>, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    let issued = op_token_commands::issue_op_token(&state, payload).await?;
    Ok(Json(issued))
}

pub async fn report_op_token_misuse(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<OpTokenMisuseAlertRequest>,
) -> Result<StatusCode, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    op_token_commands::report_op_token_misuse(&state, payload).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn handle_napcat_group_event(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<NapcatGroupMessageEvent>,
) -> Result<StatusCode, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    if !is_group_message_event(&payload) {
        return Ok(StatusCode::NO_CONTENT);
    }

    let command_text = normalize_command_text(payload.raw_message.as_deref(), payload.message.as_ref());
    if !is_issue_token_command(&command_text) {
        return Ok(StatusCode::NO_CONTENT);
    }

    let group_id = match payload.group_id {
        Some(value) if value > 0 => value,
        _ => return Ok(StatusCode::NO_CONTENT),
    };
    let operator_id = payload
        .user_id
        .filter(|value| *value > 0)
        .map(|value| value.to_string());

    let issue_request = OpTokenIssueRequest {
        server_id: None,
        operator_id,
        group_id: Some(group_id.to_string()),
    };

    let response_message = match op_token_commands::issue_op_token(&state, issue_request).await {
        Ok(issued) => format!(
            "OP token 已签发（当天有效）\n{}\n过期时间: {}\n游戏内使用: /lattice token apply <token>",
            issued.token, issued.expires_at
        ),
        Err(err) => build_issue_failure_message(&err),
    };

    state
        .alert_service
        .send_group_text(&state.config, group_id, &response_message)
        .await
        .map_err(|err| HttpError::Internal(err.to_string()))?;

    Ok(StatusCode::NO_CONTENT)
}

pub async fn get_mod_config_current(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<ServerIdQuery>,
) -> Result<Json<Option<ModConfigEnvelope>>, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    let server_id = resolve_server_id(query.server_id);
    let value = mod_config_queries::get_mod_config(&state, &server_id).await?;
    Ok(Json(value))
}

pub async fn put_mod_config_current(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<ServerIdQuery>,
    Json(payload): Json<ModConfigPutRequest>,
) -> Result<Json<ModConfigEnvelope>, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    let envelope = mod_config_commands::put_mod_config(&state, query.server_id, payload).await?;
    Ok(Json(envelope))
}

pub async fn pull_mod_config(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<ModConfigPullQuery>,
) -> Result<Json<Option<ModConfigEnvelope>>, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    let server_id = resolve_server_id(query.server_id);
    let value =
        mod_config_queries::pull_mod_config(&state, &server_id, query.after_revision).await?;
    Ok(Json(value))
}

pub async fn update_mod_config_ack(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<ModConfigAck>,
) -> Result<StatusCode, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    mod_config_commands::save_mod_config_ack(&state, payload).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn get_mod_config_ack_last(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<ServerIdQuery>,
) -> Result<Json<Option<ModConfigAck>>, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    let server_id = resolve_server_id(query.server_id);
    let ack = mod_config_queries::get_mod_config_ack(&state, &server_id).await?;
    Ok(Json(ack))
}

pub async fn stream_mod_config(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<ServerIdQuery>,
    ws: WebSocketUpgrade,
) -> Result<Response, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    let server_id = resolve_server_id(query.server_id);
    let receiver = state.mod_config_stream_hub.subscribe(&server_id).await;
    let initial = mod_config_queries::get_mod_config(&state, &server_id).await?;

    Ok(ws.on_upgrade(move |socket| async move {
        handle_mod_config_stream(socket, receiver, initial).await;
    }))
}

pub async fn alert_target_check(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> impl IntoResponse {
    if !authorize(&state.config, &headers) {
        return (
            StatusCode::UNAUTHORIZED,
            Json(AlertStatus {
                status: "unauthorized".to_string(),
                mode: "unset".to_string(),
            }),
        )
            .into_response();
    }

    let timeout_secs = state.config.request_timeout_seconds.max(1);
    let timeout_duration = Duration::from_secs(timeout_secs);
    let mode = if let Some(url) = &state.config.alert_webhook_url {
        if url.starts_with("ws://") || url.starts_with("wss://") {
            "ws"
        } else {
            "http"
        }
    } else if let Some(url) = &state.config.webhook_url {
        if url.starts_with("ws://") || url.starts_with("wss://") {
            "ws"
        } else {
            "http"
        }
    } else {
        "unset"
    };

    match timeout(
        timeout_duration,
        state.alert_service.check_alert_target(&state.config),
    )
    .await
    {
        Ok(Ok(_)) => (
            StatusCode::OK,
            Json(AlertStatus {
                status: "ok".to_string(),
                mode: mode.to_string(),
            }),
        )
            .into_response(),
        Ok(Err(err)) => {
            error!("alert target check failed: {}", err);
            (
                StatusCode::SERVICE_UNAVAILABLE,
                Json(AlertStatus {
                    status: "error".to_string(),
                    mode: mode.to_string(),
                }),
            )
                .into_response()
        }
        Err(_) => {
            error!("alert target check timeout after {}s", timeout_secs);
            (
                StatusCode::SERVICE_UNAVAILABLE,
                Json(AlertStatus {
                    status: "timeout".to_string(),
                    mode: mode.to_string(),
                }),
            )
                .into_response()
        }
    }
}

pub async fn list_alert_deliveries(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<AlertDeliveryQuery>,
) -> Result<Json<Vec<AlertDeliveryRecord>>, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    let limit = query.limit.unwrap_or(50).clamp(1, 200);
    let deliveries = state.alert_service.list_alert_deliveries(limit).await;
    Ok(Json(deliveries))
}

pub async fn get_last_alert_delivery(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Option<AlertDeliveryRecord>>, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    let last = state.alert_service.last_alert_delivery().await;
    Ok(Json(last))
}

pub async fn health_live() -> StatusCode {
    StatusCode::OK
}

pub async fn health_ready(State(state): State<AppState>) -> StatusCode {
    let timeout_secs = state.config.request_timeout_seconds.max(1);
    let timeout_duration = Duration::from_secs(timeout_secs);
    match timeout(timeout_duration, state.event_repo.ping()).await {
        Ok(Ok(_)) => StatusCode::OK,
        Ok(Err(err)) => {
            error!("ready check failed: {}", err);
            StatusCode::SERVICE_UNAVAILABLE
        }
        Err(_) => {
            error!("ready check timeout after {}s", timeout_secs);
            StatusCode::SERVICE_UNAVAILABLE
        }
    }
}

pub async fn metrics_prometheus(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> impl IntoResponse {
    if !authorize(&state.config, &headers) {
        return (StatusCode::UNAUTHORIZED, "unauthorized".to_string()).into_response();
    }
    let payload = state.metrics.render_prometheus();
    let mut headers = HeaderMap::new();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("text/plain; version=0.0.4; charset=utf-8"),
    );
    (headers, payload).into_response()
}

async fn handle_mod_config_stream(
    mut socket: WebSocket,
    mut receiver: tokio::sync::broadcast::Receiver<ModConfigEnvelope>,
    initial: Option<ModConfigEnvelope>,
) {
    if let Some(envelope) = initial {
        if send_mod_config(&mut socket, &envelope).await.is_err() {
            return;
        }
    }

    loop {
        tokio::select! {
            next = receiver.recv() => {
                match next {
                    Ok(envelope) => {
                        if send_mod_config(&mut socket, &envelope).await.is_err() {
                            break;
                        }
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(skipped)) => {
                        warn!("mod config stream lagged, skipped {} messages", skipped);
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => {
                        break;
                    }
                }
            }
            incoming = socket.next() => {
                match incoming {
                    Some(Ok(Message::Text(text))) => {
                        if text.trim().eq_ignore_ascii_case("ping") {
                            if socket.send(Message::Text("pong".into())).await.is_err() {
                                break;
                            }
                        }
                    }
                    Some(Ok(Message::Ping(bytes))) => {
                        if socket.send(Message::Pong(bytes)).await.is_err() {
                            break;
                        }
                    }
                    Some(Ok(Message::Close(_))) => break,
                    Some(Err(_)) | None => break,
                    _ => {}
                }
            }
        }
    }
}

async fn send_mod_config(socket: &mut WebSocket, envelope: &ModConfigEnvelope) -> Result<(), ()> {
    let text = serde_json::to_string(envelope).map_err(|_| ())?;
    socket
        .send(Message::Text(text.into()))
        .await
        .map_err(|_| ())
}

fn resolve_server_id(server_id: Option<String>) -> String {
    let value = server_id.unwrap_or_else(|| "server-01".to_string());
    let trimmed = value.trim();
    if trimmed.is_empty() {
        "server-01".to_string()
    } else {
        trimmed.to_lowercase()
    }
}

fn is_group_message_event(event: &NapcatGroupMessageEvent) -> bool {
    event.post_type.eq_ignore_ascii_case("message")
        && event.message_type.eq_ignore_ascii_case("group")
}

fn normalize_command_text(raw_message: Option<&str>, segments: Option<&Value>) -> String {
    let mut text = raw_message
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToOwned::to_owned)
        .unwrap_or_else(|| extract_text_from_segments(segments));
    if text.is_empty() {
        return text;
    }

    text = text.replace('／', "/");
    let filtered = text
        .split_whitespace()
        .filter(|part| {
            let lower = part.to_ascii_lowercase();
            !(lower.starts_with("[cq:at,qq=") && lower.ends_with(']'))
        })
        .collect::<Vec<_>>()
        .join(" ");
    filtered.trim().to_string()
}

fn extract_text_from_segments(segments: Option<&Value>) -> String {
    let Some(Value::Array(items)) = segments else {
        return String::new();
    };
    let mut out = String::new();
    for item in items {
        let Some(obj) = item.as_object() else {
            continue;
        };
        let Some(kind) = obj.get("type").and_then(Value::as_str) else {
            continue;
        };
        if kind != "text" {
            continue;
        }
        let text = obj
            .get("data")
            .and_then(Value::as_object)
            .and_then(|data| data.get("text"))
            .and_then(Value::as_str)
            .unwrap_or("");
        if !text.is_empty() {
            out.push_str(text);
        }
    }
    out
}

fn is_issue_token_command(text: &str) -> bool {
    matches!(text.trim(), "/申请" | "申请" | "/申请token" | "申请token")
}

fn build_issue_failure_message(err: &AppError) -> String {
    match err {
        AppError::Unauthorized => "申请失败：当前群未授权申请 OP token".to_string(),
        AppError::BadRequest(message) => format!("申请失败：{}", message),
        AppError::Internal(_) => "申请失败：后端内部错误".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn normalize_command_text_supports_raw_message() {
        let text = normalize_command_text(Some("/申请"), None);
        assert_eq!(text, "/申请");
    }

    #[test]
    fn normalize_command_text_strips_cq_at_prefix() {
        let text = normalize_command_text(Some("[CQ:at,qq=123456] /申请"), None);
        assert_eq!(text, "/申请");
    }

    #[test]
    fn normalize_command_text_falls_back_to_segments() {
        let segments = json!([
            {"type":"at","data":{"qq":"123456"}},
            {"type":"text","data":{"text":" /申请 "}}
        ]);
        let text = normalize_command_text(None, Some(&segments));
        assert_eq!(text, "/申请");
    }

    #[test]
    fn issue_command_match_accepts_apply_aliases() {
        assert!(is_issue_token_command("/申请"));
        assert!(is_issue_token_command("申请"));
        assert!(is_issue_token_command("/申请token"));
        assert!(!is_issue_token_command("/别的命令"));
    }
}
