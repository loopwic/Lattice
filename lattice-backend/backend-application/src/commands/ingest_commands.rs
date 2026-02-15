use tracing::warn;
use crate::AppState;
use backend_domain::IngestEvent;
use crate::AppError;

pub async fn process_ingest_events(
    state: &AppState,
    events: Vec<IngestEvent>,
) -> Result<(), AppError> {
    if let Err(err) = state.event_repo.insert_events(&events).await {
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
        if let Err(err) = state.anomaly_repo.insert_anomalies(&anomalies).await {
            warn!("failed to insert anomalies: {}", err);
        }
        state.metrics.record_anomalies(anomalies.len());
        state.alert_service.spawn_alerts(state.config.clone(), anomalies.clone());
    }

    state.metrics.record_ingest(events.len());
    Ok(())
}
