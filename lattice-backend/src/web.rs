use axum::extract::{Query, State};
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::IntoResponse;
use axum::{Json, Router};
use chrono::Local;
use tokio::time::{timeout, Duration};
use tracing::{error, warn};

use crate::app::AppState;
use crate::config::{load_rcon_config, save_item_registry, save_key_items, save_rcon_config, RconConfig};
use crate::error::AppError;
use crate::ingest::{authorize, parse_events};
use crate::model::{
    AnomalyQuery,
    AnomalyRow,
    ItemRegistryEntry,
    ItemRegistryPayload,
    ItemRegistryQuery,
    ItemRegistryUpdateQuery,
    KeyItemRule,
    KeyItemRuleApi,
    StorageScanQuery,
    StorageScanRow,
    TaskProgressUpdate,
    TaskStatus,
};
use crate::alert;

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/v2/ingest/events", axum::routing::post(ingest_items))
        .route("/v2/detect/anomalies", axum::routing::get(list_anomalies))
        .route("/v2/detect/rules", axum::routing::get(list_key_items).put(update_key_items))
        .route(
            "/v2/query/item-registry",
            axum::routing::get(list_item_registry).put(update_item_registry),
        )
        .route(
            "/v2/ops/rcon-config",
            axum::routing::get(get_rcon_config).put(update_rcon_config),
        )
        .route(
            "/v2/ops/task-progress",
            axum::routing::get(get_task_progress).put(update_task_progress),
        )
        .route("/v2/detect/storage-scan", axum::routing::get(list_storage_scan))
        .route(
            "/v2/ops/alert-target/check",
            axum::routing::get(alert_target_check),
        )
        .route("/v2/ops/health/live", axum::routing::get(health_live))
        .route("/v2/ops/health/ready", axum::routing::get(health_ready))
        .route(
            "/v2/ops/metrics/prometheus",
            axum::routing::get(metrics_prometheus),
        )
        .with_state(state)
}

async fn ingest_items(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> Result<StatusCode, AppError> {
    if !authorize(&state.config, &headers) {
        return Err(AppError::Unauthorized);
    }

    let events = parse_events(&headers, &body).map_err(|err| {
        error!("failed to parse ingest body: {}", err);
        AppError::BadRequest(err.to_string())
    })?;
    let original_len = events.len();
    let events = events
        .into_iter()
        .filter(|event| {
            !(event.item_id.trim().is_empty()
                || event.item_id == "minecraft:air"
                || event.count <= 0)
        })
        .collect::<Vec<_>>();
    if events.is_empty() {
        if original_len > 0 {
            warn!(
                "dropped {} invalid events (empty item_id/air/<=0 count)",
                original_len
            );
        }
        return Ok(StatusCode::NO_CONTENT);
    }
    if events.len() != original_len {
        warn!(
            "dropped {} invalid events (empty item_id/air/<=0 count)",
            original_len - events.len()
        );
    }

    if let Err(err) = state.repo.insert_events(&events).await {
        error!("failed to insert events: {}", err);
        state.metrics.record_ingest_error();
        return Err(AppError::Internal(err.into()));
    }

    let rules_snapshot = { state.key_rules.read().await.clone() };
    let anomalies = {
        let mut analyzer = state.analyzer.lock().await;
        analyzer.analyze_batch(
            &events,
            &rules_snapshot,
            (state.config.transfer_window_seconds * 1000) as i64,
            (state.config.key_item_window_minutes * 60_000) as i64,
            if state.config.strict_enabled {
                (state.config.strict_pickup_window_seconds * 1000) as i64
            } else {
                0
            },
            if state.config.strict_enabled {
                state.config.strict_pickup_threshold as i64
            } else {
                0
            },
        )
    };

    if !anomalies.is_empty() {
        if let Err(err) = state.repo.insert_anomalies(&anomalies).await {
            warn!("failed to insert anomalies: {}", err);
        }
        state.metrics.record_anomalies(anomalies.len());
        alert::spawn_alerts(&state.config, &anomalies);
    }

    state.metrics.record_ingest(events.len());
    Ok(StatusCode::OK)
}

async fn list_anomalies(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<AnomalyQuery>,
) -> Result<Json<Vec<AnomalyRow>>, AppError> {
    if !authorize(&state.config, &headers) {
        return Err(AppError::Unauthorized);
    }
    let date = query
        .date
        .unwrap_or_else(|| chrono::Local::now().format("%Y-%m-%d").to_string());
    let rows = state
        .repo
        .fetch_anomalies(&date, query.player.as_deref())
        .await
        .map_err(|err| {
            error!("failed to fetch anomalies: {}", err);
            AppError::Internal(err.into())
        })?;
    Ok(Json(rows))
}

async fn list_storage_scan(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<StorageScanQuery>,
) -> Result<Json<Vec<StorageScanRow>>, AppError> {
    if !authorize(&state.config, &headers) {
        return Err(AppError::Unauthorized);
    }
    let date = query
        .date
        .unwrap_or_else(|| Local::now().format("%Y-%m-%d").to_string());
    if let Err(err) = crate::utils::parse_date(&date) {
        return Err(AppError::BadRequest(format!("invalid date: {}", err)));
    }

    let item = query.item.as_deref().map(|value| value.trim().to_lowercase());
    if let Some(item_id) = item.as_deref() {
        if item_id.is_empty() {
            return Err(AppError::BadRequest("item is empty".to_string()));
        }
        if !item_id.contains(':') {
            return Err(AppError::BadRequest("item must be namespace:path".to_string()));
        }
        if !item_id.chars().all(|c| {
            c.is_ascii_lowercase()
                || c.is_ascii_digit()
                || c == ':'
                || c == '_'
                || c == '-'
                || c == '.'
                || c == '/'
        }) {
            return Err(AppError::BadRequest("item contains invalid characters".to_string()));
        }
    }

    let limit = query.limit.unwrap_or(200).clamp(1, 2000);
    let events = state
        .repo
        .fetch_storage_scan_events(&date, item.as_deref(), limit)
        .await
        .map_err(|err| {
            error!("failed to fetch storage scan events: {}", err);
            AppError::Internal(err.into())
        })?;

    let rules = state.key_rules.read().await;
    let mut rows = Vec::new();
    for event in events {
        let Some(rule) = rules.get(&event.item_id) else {
            continue;
        };
        let threshold = rule.effective_threshold();
        if threshold == 0 {
            continue;
        }
        if event.count <= 0 {
            continue;
        }
        if event.count as u64 <= threshold {
            continue;
        }
        let risk_level = rule.effective_risk_level();
        rows.push(StorageScanRow {
            event_time: event.event_time,
            item_id: event.item_id,
            count: event.count,
            storage_mod: event.storage_mod,
            storage_id: event.storage_id,
            dim: event.dim,
            x: event.x,
            y: event.y,
            z: event.z,
            rule_id: "R12".to_string(),
            threshold,
            risk_level,
            reason: format!(
                "Storage snapshot exceeds threshold (count={}, threshold={})",
                event.count, threshold
            ),
        });
    }

    Ok(Json(rows))
}

#[derive(serde::Deserialize)]
struct KeyItemRulesPayload {
    rules: Vec<KeyItemRuleApi>,
}

async fn list_key_items(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Vec<KeyItemRuleApi>>, AppError> {
    if !authorize(&state.config, &headers) {
        return Err(AppError::Unauthorized);
    }
    let rules = state.key_rules.read().await;
    let mut list = rules.values().map(KeyItemRuleApi::from).collect::<Vec<_>>();
    list.sort_by(|a, b| a.item_id.cmp(&b.item_id));
    Ok(Json(list))
}

async fn update_key_items(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<KeyItemRulesPayload>,
) -> Result<StatusCode, AppError> {
    if !authorize(&state.config, &headers) {
        return Err(AppError::Unauthorized);
    }
    let mut rules = Vec::new();
    for rule in payload.rules.into_iter() {
        let normalized = rule.normalized();
        if normalized.item_id.is_empty() {
            return Err(AppError::BadRequest("item_id is required".to_string()));
        }
        if !normalized.item_id.contains(':') {
            return Err(AppError::BadRequest(format!(
                "invalid item_id '{}'",
                normalized.item_id
            )));
        }
        if normalized.threshold == 0 {
            return Err(AppError::BadRequest(format!(
                "threshold must be > 0 for '{}'",
                normalized.item_id
            )));
        }
        let risk = normalized.risk_level.as_str();
        if risk != "LOW" && risk != "MEDIUM" && risk != "HIGH" {
            return Err(AppError::BadRequest(format!(
                "invalid risk_level '{}' for '{}'",
                normalized.risk_level, normalized.item_id
            )));
        }
        rules.push(KeyItemRule::from(normalized));
    }
    rules.sort_by(|a, b| a.item_id.cmp(&b.item_id));
    save_key_items(&state.config.key_items_path, &rules)
        .await
        .map_err(|err| AppError::Internal(err.into()))?;

    let map = rules
        .into_iter()
        .map(|rule| (rule.item_id.clone(), rule))
        .collect();
    *state.key_rules.write().await = map;
    Ok(StatusCode::NO_CONTENT)
}

async fn list_item_registry(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<ItemRegistryQuery>,
) -> Result<Json<Vec<ItemRegistryEntry>>, AppError> {
    if !authorize(&state.config, &headers) {
        return Err(AppError::Unauthorized);
    }
    let limit = query.limit.unwrap_or(50).clamp(1, 200);
    let query_text = query.query.unwrap_or_default().trim().to_lowercase();
    let lang = query.lang.unwrap_or_else(|| "zh_cn".to_string()).to_lowercase();
    let items = state.item_registry.read().await;
    let mut results = Vec::new();
    for entry in items.iter() {
        if query_text.is_empty()
            || entry.item_id.to_lowercase().contains(&query_text)
            || entry
                .name
                .as_ref()
                .map(|name| name.to_lowercase().contains(&query_text))
                .unwrap_or(false)
            || entry
                .names
                .as_ref()
                .and_then(|names| names.get(&lang))
                .map(|name| name.to_lowercase().contains(&query_text))
                .unwrap_or(false)
            || entry
                .names
                .as_ref()
                .map(|names| {
                    names
                        .values()
                        .any(|name| name.to_lowercase().contains(&query_text))
                })
                .unwrap_or(false)
        {
            results.push(entry.clone());
            if results.len() >= limit {
                break;
            }
        }
    }
    Ok(Json(results))
}

async fn update_item_registry(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<ItemRegistryUpdateQuery>,
    Json(payload): Json<ItemRegistryPayload>,
) -> Result<StatusCode, AppError> {
    if !authorize(&state.config, &headers) {
        return Err(AppError::Unauthorized);
    }
    let mut incoming = payload
        .items
        .into_iter()
        .filter_map(|mut item| {
            item.item_id = item.item_id.trim().to_lowercase();
            if item.item_id.is_empty() || !item.item_id.contains(':') {
                return None;
            }
            if let Some(name) = &item.name {
                let trimmed = name.trim();
                item.name = if trimmed.is_empty() {
                    None
                } else {
                    Some(trimmed.to_string())
                };
            }
            if let Some(names) = &mut item.names {
                names.retain(|_, v| !v.trim().is_empty());
                if names.is_empty() {
                    item.names = None;
                } else {
                    let normalized = names
                        .iter()
                        .map(|(k, v)| (k.trim().to_lowercase(), v.trim().to_string()))
                        .collect::<std::collections::HashMap<_, _>>();
                    item.names = Some(normalized);
                }
            }
            if item.namespace.is_none() || item.path.is_none() {
                let mut parts = item.item_id.splitn(2, ':');
                let namespace = parts.next().unwrap_or("").to_string();
                let path = parts.next().unwrap_or("").to_string();
                if item.namespace.is_none() && !namespace.is_empty() {
                    item.namespace = Some(namespace);
                }
                if item.path.is_none() && !path.is_empty() {
                    item.path = Some(path);
                }
            }
            Some(item)
        })
        .collect::<Vec<_>>();
    incoming.sort_by(|a, b| a.item_id.cmp(&b.item_id));

    let mode = query.mode.unwrap_or_else(|| "replace".to_string());
    let mut merged = if mode == "append" {
        state.item_registry.read().await.clone()
    } else {
        Vec::new()
    };
    if !incoming.is_empty() {
        let mut map = std::collections::HashMap::new();
        for entry in merged.into_iter() {
            map.insert(entry.item_id.clone(), entry);
        }
        for entry in incoming.into_iter() {
            map.insert(entry.item_id.clone(), entry);
        }
        merged = map.into_values().collect::<Vec<_>>();
        merged.sort_by(|a, b| a.item_id.cmp(&b.item_id));
    }

    save_item_registry(&state.config.item_registry_path, &merged)
        .await
        .map_err(|err| AppError::Internal(err.into()))?;
    *state.item_registry.write().await = merged;
    Ok(StatusCode::NO_CONTENT)
}

async fn get_rcon_config(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<RconConfig>, AppError> {
    if !authorize(&state.config, &headers) {
        return Err(AppError::Unauthorized);
    }
    let config = load_rcon_config()
        .await
        .map_err(|err| AppError::Internal(err.into()))?;
    Ok(Json(config))
}

async fn update_rcon_config(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<RconConfig>,
) -> Result<StatusCode, AppError> {
    if !authorize(&state.config, &headers) {
        return Err(AppError::Unauthorized);
    }
    save_rcon_config(&payload)
        .await
        .map_err(|err| AppError::Internal(err.into()))?;
    Ok(StatusCode::NO_CONTENT)
}

async fn get_task_progress(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<TaskStatus>, AppError> {
    if !authorize(&state.config, &headers) {
        return Err(AppError::Unauthorized);
    }
    let status = state.task_status.read().await.clone();
    Ok(Json(status))
}

async fn update_task_progress(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<TaskProgressUpdate>,
) -> Result<StatusCode, AppError> {
    if !authorize(&state.config, &headers) {
        return Err(AppError::Unauthorized);
    }
    let mut status = state.task_status.write().await;
    let now = chrono::Utc::now().timestamp_millis();
    let update = crate::model::TaskProgress {
        running: payload.running,
        total: payload.total,
        done: payload.done,
        updated_at: now,
    };
    let key = payload.task.trim().to_lowercase();
    if key == "audit" {
        status.audit = update;
    } else if key == "scan" {
        status.scan = update;
    }
    Ok(StatusCode::NO_CONTENT)
}

#[derive(serde::Serialize)]
struct AlertStatus {
    status: String,
    mode: String,
}

async fn alert_target_check(
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

    match timeout(timeout_duration, alert::check_alert_target(&state.config)).await {
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

async fn health_live() -> StatusCode {
    StatusCode::OK
}

async fn health_ready(State(state): State<AppState>) -> StatusCode {
    let timeout_secs = state.config.request_timeout_seconds.max(1);
    let timeout_duration = Duration::from_secs(timeout_secs);
    match timeout(timeout_duration, state.repo.ping()).await {
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

async fn metrics_prometheus(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> impl IntoResponse {
    if !authorize(&state.config, &headers) {
        return (
            StatusCode::UNAUTHORIZED,
            "unauthorized".to_string(),
        )
            .into_response();
    }
    let payload = state.metrics.render_prometheus();
    let mut headers = HeaderMap::new();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("text/plain; version=0.0.4; charset=utf-8"),
    );
    (headers, payload).into_response()
}
