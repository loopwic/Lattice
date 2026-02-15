use std::collections::HashMap;
use std::sync::Arc;

use backend_domain::ports::{AlertService, AnomalyRepository, ConfigRepository, EventRepository};
use backend_domain::services::Analyzer;
use backend_domain::{ItemRegistryEntry, KeyItemRule, RuntimeConfig, TaskStatus};
use tokio::sync::{Mutex, RwLock};

use crate::Metrics;

#[derive(Clone)]
pub struct AppState {
    pub config: RuntimeConfig,
    pub event_repo: Arc<dyn EventRepository>,
    pub anomaly_repo: Arc<dyn AnomalyRepository>,
    pub config_repo: Arc<dyn ConfigRepository>,
    pub alert_service: Arc<dyn AlertService>,
    pub analyzer: Arc<Mutex<Analyzer>>,
    pub key_rules: Arc<RwLock<HashMap<String, KeyItemRule>>>,
    pub item_registry: Arc<RwLock<Vec<ItemRegistryEntry>>>,
    pub metrics: Arc<Metrics>,
    pub task_status: Arc<RwLock<TaskStatus>>,
}
