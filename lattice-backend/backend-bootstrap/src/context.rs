use std::sync::Arc;

use anyhow::Result;
use clickhouse::Client;
use tokio::sync::{Mutex, RwLock};

use backend_application::{AppState, Metrics};
use backend_domain::{Analyzer, ConfigRepository, TaskStatus};
use backend_infrastructure::{
    AppConfig, ClickhouseRepo, ConfigFileRepository, DefaultAlertService,
};

pub struct AppContext {
    pub state: AppState,
}

impl AppContext {
    pub async fn new() -> Result<Self> {
        let config = AppConfig::load().await?;
        let runtime_config = config.to_runtime_config();
        let db_config = config.to_db_config();

        let mut clickhouse = Client::default()
            .with_url(&db_config.clickhouse_url)
            .with_database(&db_config.clickhouse_database);
        if let Some(user) = &db_config.clickhouse_user {
            clickhouse = clickhouse.with_user(user);
        }
        if let Some(password) = &db_config.clickhouse_password {
            clickhouse = clickhouse.with_password(password);
        }

        let repo = Arc::new(ClickhouseRepo::new(
            clickhouse,
            db_config.clickhouse_database.clone(),
        ));
        repo.ensure_schema().await?;

        let config_repo = Arc::new(ConfigFileRepository::new());
        let key_rules = config_repo
            .load_key_items(&runtime_config.key_items_path)
            .await
            .unwrap_or_default();
        let item_registry = config_repo
            .load_item_registry(&runtime_config.item_registry_path)
            .await
            .unwrap_or_default();

        let state = AppState {
            config: runtime_config,
            event_repo: repo.clone(),
            anomaly_repo: repo,
            config_repo,
            alert_service: Arc::new(DefaultAlertService::new()),
            analyzer: Arc::new(Mutex::new(Analyzer::default())),
            key_rules: Arc::new(RwLock::new(key_rules)),
            item_registry: Arc::new(RwLock::new(item_registry)),
            metrics: Arc::new(Metrics::default()),
            task_status: Arc::new(RwLock::new(TaskStatus::default())),
        };

        Ok(Self { state })
    }
}
