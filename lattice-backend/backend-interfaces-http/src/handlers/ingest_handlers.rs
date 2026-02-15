use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use tracing::{error, warn};

use backend_application::commands::ingest_commands;
use backend_application::AppState;

use crate::error::HttpError;
use crate::middleware::{authorize, parse_events};

pub async fn ingest_items(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> Result<StatusCode, HttpError> {
    if !authorize(&state.config, &headers) {
        return Err(HttpError::Unauthorized);
    }

    let events = parse_events(&headers, &body).map_err(|err| {
        error!("failed to parse ingest body: {}", err);
        HttpError::BadRequest(err.to_string())
    })?;
    let original_len = events.len();
    let events = events
        .into_iter()
        .filter(|event| {
            !(event.item_id.trim().is_empty() || event.item_id == "minecraft:air" || event.count <= 0)
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

    ingest_commands::process_ingest_events(&state, events).await?;
    Ok(StatusCode::OK)
}
