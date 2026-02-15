use axum::extract::{Query, State};
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::IntoResponse;
use axum::Json;
use tokio::time::{timeout, Duration};
use tracing::error;

use backend_application::commands::task_progress_commands;
use backend_application::queries::task_progress_queries;
use backend_application::AppState;
use backend_domain::{AlertDeliveryRecord, RconConfig, TaskProgressUpdate, TaskStatus};

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
    task_progress_commands::update_task_progress(&state, payload).await;
    Ok(StatusCode::NO_CONTENT)
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
