use chrono::Local;
use tracing::error;

use crate::AppState;
use backend_domain::{StorageScanQuery, StorageScanRow};
use crate::AppError;

pub async fn list_storage_scan(
    state: &AppState,
    query: StorageScanQuery,
) -> Result<Vec<StorageScanRow>, AppError> {
    let date = query
        .date
        .unwrap_or_else(|| Local::now().format("%Y-%m-%d").to_string());
    if let Err(err) = backend_domain::parse_date(&date) {
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
        .event_repo
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

    Ok(rows)
}
