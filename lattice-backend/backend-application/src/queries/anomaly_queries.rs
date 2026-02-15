use chrono::Local;
use tracing::error;

use crate::AppState;
use backend_domain::{AnomalyQuery, AnomalyRow};
use crate::AppError;

pub async fn list_anomalies(
    state: &AppState,
    query: AnomalyQuery,
) -> Result<Vec<AnomalyRow>, AppError> {
    let date = query
        .date
        .unwrap_or_else(|| Local::now().format("%Y-%m-%d").to_string());
    let rows = state
        .anomaly_repo
        .fetch_anomalies(&date, query.player.as_deref())
        .await
        .map_err(|err| {
            error!("failed to fetch anomalies: {}", err);
            AppError::Internal(err.into())
        })?;
    Ok(rows)
}
