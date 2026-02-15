use axum::Router;

use backend_application::AppState;

use crate::handlers::{detect_handlers, ingest_handlers, ops_handlers, query_handlers};

pub fn build_router(state: AppState) -> Router {
    Router::new()
        .route(
            "/v2/ingest/events",
            axum::routing::post(ingest_handlers::ingest_items),
        )
        .route(
            "/v2/detect/anomalies",
            axum::routing::get(detect_handlers::list_anomalies),
        )
        .route(
            "/v2/detect/rules",
            axum::routing::get(detect_handlers::list_key_items)
                .put(detect_handlers::update_key_items),
        )
        .route(
            "/v2/query/item-registry",
            axum::routing::get(query_handlers::list_item_registry)
                .put(query_handlers::update_item_registry),
        )
        .route(
            "/v2/ops/rcon-config",
            axum::routing::get(ops_handlers::get_rcon_config).put(ops_handlers::update_rcon_config),
        )
        .route(
            "/v2/ops/task-progress",
            axum::routing::get(ops_handlers::get_task_progress)
                .put(ops_handlers::update_task_progress),
        )
        .route(
            "/v2/detect/storage-scan",
            axum::routing::get(detect_handlers::list_storage_scan),
        )
        .route(
            "/v2/ops/alert-target/check",
            axum::routing::get(ops_handlers::alert_target_check),
        )
        .route(
            "/v2/ops/alert-deliveries",
            axum::routing::get(ops_handlers::list_alert_deliveries),
        )
        .route(
            "/v2/ops/alert-deliveries/last",
            axum::routing::get(ops_handlers::get_last_alert_delivery),
        )
        .route(
            "/v2/ops/health/live",
            axum::routing::get(ops_handlers::health_live),
        )
        .route(
            "/v2/ops/health/ready",
            axum::routing::get(ops_handlers::health_ready),
        )
        .route(
            "/v2/ops/metrics/prometheus",
            axum::routing::get(ops_handlers::metrics_prometheus),
        )
        .with_state(state)
}
