use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use anyhow::Result;
use axum::Router;
use clickhouse::Client;
use tokio::net::TcpListener;
use tokio::sync::{Mutex, RwLock};
use tower_http::cors::CorsLayer;
use tower_http::limit::RequestBodyLimitLayer;
use tower_http::timeout::TimeoutLayer;
use tower_http::trace::TraceLayer;
use tracing::info;

use crate::analyzer::Analyzer;
use crate::config::{load_item_registry, load_key_items, AppConfig};
use crate::db::ClickhouseRepo;
use crate::metrics::Metrics;
use crate::model::{ItemRegistryEntry, KeyItemRule, TaskStatus};
use crate::report::schedule_reports;
use crate::web;

#[derive(Clone)]
pub struct AppState {
    pub config: AppConfig,
    pub repo: ClickhouseRepo,
    pub analyzer: Arc<Mutex<Analyzer>>,
    pub key_rules: Arc<RwLock<HashMap<String, KeyItemRule>>>,
    pub item_registry: Arc<RwLock<Vec<ItemRegistryEntry>>>,
    pub metrics: Arc<Metrics>,
    pub task_status: Arc<RwLock<TaskStatus>>,
}

pub async fn run() -> Result<()> {
    let _ = tracing_subscriber::fmt().with_env_filter("info").try_init();

    let config = AppConfig::load().await?;
    info!(
        clickhouse_url = %config.clickhouse_url,
        clickhouse_database = %config.clickhouse_database,
        clickhouse_user = %config
            .clickhouse_user
            .as_deref()
            .unwrap_or("<none>"),
        clickhouse_password_set = config.clickhouse_password.is_some(),
        "config loaded"
    );
    let mut clickhouse = Client::default()
        .with_url(&config.clickhouse_url)
        .with_database(&config.clickhouse_database);
    if let Some(user) = &config.clickhouse_user {
        clickhouse = clickhouse.with_user(user);
    }
    if let Some(password) = &config.clickhouse_password {
        clickhouse = clickhouse.with_password(password);
    }
    let repo = ClickhouseRepo::new(clickhouse, config.clickhouse_database.clone());
    repo.ensure_schema().await?;

    let key_rules = load_key_items(&config.key_items_path).await.unwrap_or_default();
    let item_registry = load_item_registry(&config.item_registry_path).await.unwrap_or_default();

    let state = AppState {
        config: config.clone(),
        repo: repo.clone(),
        analyzer: Arc::new(Mutex::new(Analyzer::default())),
        key_rules: Arc::new(RwLock::new(key_rules)),
        item_registry: Arc::new(RwLock::new(item_registry)),
        metrics: Arc::new(Metrics::default()),
        task_status: Arc::new(RwLock::new(TaskStatus::default())),
    };

    let app = build_router(state.clone(), &config);
    tokio::spawn(schedule_reports(state.clone()));

    let addr: std::net::SocketAddr = config.bind_addr.parse()?;
    let listener = TcpListener::bind(addr).await?;
    info!("listening on {}", addr);
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await?;
    Ok(())
}

fn build_router(state: AppState, config: &AppConfig) -> Router {
    web::router(state)
        .layer(CorsLayer::permissive())
        .layer(RequestBodyLimitLayer::new(
            usize::try_from(config.max_body_bytes).unwrap_or(usize::MAX),
        ))
        .layer(TimeoutLayer::new(Duration::from_secs(
            config.request_timeout_seconds,
        )))
        .layer(TraceLayer::new_for_http())
}

async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };

    #[cfg(unix)]
    let terminate = async {
        use tokio::signal::unix::{signal, SignalKind};
        let mut sigterm = signal(SignalKind::terminate()).expect("sigterm handler");
        sigterm.recv().await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}
