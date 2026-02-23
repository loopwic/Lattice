use chrono::Local;
use tracing::error;

use crate::AppState;
use crate::AppError;
use backend_domain::{KeyItemRule, PagedResult, StorageScanEventRow, StorageScanQuery, StorageScanRow};

const DEFAULT_PAGE: usize = 1;
const DEFAULT_PAGE_SIZE: usize = 50;
const ALLOWED_PAGE_SIZES: [usize; 4] = [25, 50, 100, 200];

pub async fn list_storage_scan(
    state: &AppState,
    query: StorageScanQuery,
) -> Result<PagedResult<StorageScanRow>, AppError> {
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

    let (page, page_size) = normalize_page(query.page, query.page_size)?;
    let total_raw_u64 = state
        .event_repo
        .count_storage_scan_events(&date, item.as_deref())
        .await
        .map_err(|err| {
            error!("failed to count storage scan events: {}", err);
            AppError::Internal(err.into())
        })?;
    let total_raw = usize::try_from(total_raw_u64).unwrap_or(usize::MAX);
    if total_raw == 0 {
        return Ok(PagedResult {
            items: Vec::new(),
            page,
            page_size,
            total_items: 0,
            total_pages: 1,
        });
    }

    // Storage scan threshold is rule-dependent, so we materialize filtered rows first,
    // then apply stable paging on the filtered result set.
    let rules = state.key_rules.read().await.clone();
    let mut filtered_rows = Vec::new();
    let mut current_offset = 0usize;
    const CHUNK_SIZE: usize = 200;
    while current_offset < total_raw {
        let events = state
            .event_repo
            .fetch_storage_scan_events_page(&date, item.as_deref(), current_offset, CHUNK_SIZE)
            .await
            .map_err(|err| {
                error!("failed to fetch storage scan events: {}", err);
                AppError::Internal(err.into())
            })?;
        if events.is_empty() {
            break;
        }
        for event in events.iter() {
            if let Some(row) = to_storage_scan_row(event, &rules) {
                filtered_rows.push(row);
            }
        }
        current_offset = current_offset.saturating_add(events.len());
    }

    let total_items = filtered_rows.len();
    let total_pages = if total_items == 0 {
        1
    } else {
        (total_items + page_size - 1) / page_size
    };
    let start = (page - 1).saturating_mul(page_size);
    let items = if start >= total_items {
        Vec::new()
    } else {
        filtered_rows
            .into_iter()
            .skip(start)
            .take(page_size)
            .collect()
    };

    Ok(PagedResult {
        items,
        page,
        page_size,
        total_items,
        total_pages,
    })
}

fn to_storage_scan_row(
    event: &StorageScanEventRow,
    rules: &std::collections::HashMap<String, KeyItemRule>,
) -> Option<StorageScanRow> {
    let rule = rules.get(&event.item_id)?;
    let threshold = rule.effective_threshold();
    if threshold == 0 {
        return None;
    }
    if event.count <= 0 {
        return None;
    }
    if event.count as u64 <= threshold {
        return None;
    }
    let risk_level = rule.effective_risk_level();
    Some(StorageScanRow {
        event_time: event.event_time,
        item_id: event.item_id.clone(),
        count: event.count,
        storage_mod: event.storage_mod.clone(),
        storage_id: event.storage_id.clone(),
        dim: event.dim.clone(),
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
    })
}

fn normalize_page(page: Option<usize>, page_size: Option<usize>) -> Result<(usize, usize), AppError> {
    let current_page = page.unwrap_or(DEFAULT_PAGE);
    if current_page == 0 {
        return Err(AppError::BadRequest("page must be >= 1".to_string()));
    }

    let size = page_size.unwrap_or(DEFAULT_PAGE_SIZE);
    if !ALLOWED_PAGE_SIZES.contains(&size) {
        return Err(AppError::BadRequest(
            "page_size must be one of: 25, 50, 100, 200".to_string(),
        ));
    }
    Ok((current_page, size))
}
