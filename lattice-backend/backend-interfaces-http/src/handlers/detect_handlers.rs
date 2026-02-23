use axum::extract::{Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::Json;

use backend_application::commands::key_item_commands;
use backend_application::queries::{anomaly_queries, key_item_queries, storage_scan_queries};
use backend_application::AppState;
use backend_domain::{AnomalyQuery, AnomalyRow, KeyItemRuleApi, PagedResult, StorageScanQuery, StorageScanRow};

use crate::error::HttpError;
use crate::middleware::authorize;

#[derive(serde::Deserialize)]
pub struct KeyItemRulesPayload {
    pub rules: Vec<KeyItemRuleApi>,
}

pub async fn list_anomalies(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<AnomalyQuery>,
) -> Result<Json<PagedResult<AnomalyRow>>, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    let rows = anomaly_queries::list_anomalies(&state, query).await?;
    Ok(Json(rows))
}

pub async fn list_storage_scan(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<StorageScanQuery>,
) -> Result<Json<PagedResult<StorageScanRow>>, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    let rows = storage_scan_queries::list_storage_scan(&state, query).await?;
    Ok(Json(rows))
}

pub async fn list_key_items(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Vec<KeyItemRuleApi>>, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    let list = key_item_queries::list_key_items(&state).await?;
    Ok(Json(list))
}

pub async fn update_key_items(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<KeyItemRulesPayload>,
) -> Result<StatusCode, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    key_item_commands::update_key_items(&state, payload.rules).await?;
    Ok(StatusCode::NO_CONTENT)
}
