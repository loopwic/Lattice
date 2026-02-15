use axum::extract::{Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::Json;

use backend_application::commands::item_registry_commands;
use backend_application::queries::item_registry_queries;
use backend_application::AppState;
use backend_domain::{ItemRegistryEntry, ItemRegistryPayload, ItemRegistryQuery, ItemRegistryUpdateQuery};

use crate::error::HttpError;
use crate::middleware::authorize;

pub async fn list_item_registry(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<ItemRegistryQuery>,
) -> Result<Json<Vec<ItemRegistryEntry>>, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    let results = item_registry_queries::list_item_registry(&state, query).await?;
    Ok(Json(results))
}

pub async fn update_item_registry(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<ItemRegistryUpdateQuery>,
    Json(payload): Json<ItemRegistryPayload>,
) -> Result<StatusCode, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }
    item_registry_commands::update_item_registry(&state, query, payload).await?;
    Ok(StatusCode::NO_CONTENT)
}
