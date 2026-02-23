use chrono::Local;
use tracing::error;

use crate::AppState;
use crate::AppError;
use backend_domain::{AnomalyQuery, AnomalyRow, PagedResult};

const DEFAULT_PAGE: usize = 1;
const DEFAULT_PAGE_SIZE: usize = 50;
const ALLOWED_PAGE_SIZES: [usize; 4] = [25, 50, 100, 200];

pub async fn list_anomalies(
    state: &AppState,
    query: AnomalyQuery,
) -> Result<PagedResult<AnomalyRow>, AppError> {
    let date = query
        .date
        .unwrap_or_else(|| Local::now().format("%Y-%m-%d").to_string());
    if let Err(err) = backend_domain::parse_date(&date) {
        return Err(AppError::BadRequest(format!("invalid date: {}", err)));
    }

    let (page, page_size) = normalize_page(query.page, query.page_size)?;
    let offset = (page - 1).saturating_mul(page_size);

    let total_items_u64 = state
        .anomaly_repo
        .count_anomalies(&date, query.player.as_deref())
        .await
        .map_err(|err| {
            error!("failed to count anomalies: {}", err);
            AppError::Internal(err.into())
        })?;
    let total_items = usize::try_from(total_items_u64).unwrap_or(usize::MAX);
    let total_pages = if total_items == 0 {
        1
    } else {
        (total_items + page_size - 1) / page_size
    };

    let items = state
        .anomaly_repo
        .fetch_anomalies_page(&date, query.player.as_deref(), offset, page_size)
        .await
        .map_err(|err| {
            error!("failed to fetch anomalies: {}", err);
            AppError::Internal(err.into())
        })?;

    Ok(PagedResult {
        items,
        page,
        page_size,
        total_items,
        total_pages,
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
