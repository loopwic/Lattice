use std::sync::Arc;

use async_trait::async_trait;
use backend_domain::ports::{AlertService, HealthCheckService};
use backend_domain::{EventRepository, RuntimeConfig};

pub struct DefaultHealthService {
    event_repo: Arc<dyn EventRepository>,
    alert_service: Arc<dyn AlertService>,
    config: RuntimeConfig,
}

impl DefaultHealthService {
    pub fn new(
        event_repo: Arc<dyn EventRepository>,
        alert_service: Arc<dyn AlertService>,
        config: RuntimeConfig,
    ) -> Self {
        Self {
            event_repo,
            alert_service,
            config,
        }
    }
}

#[async_trait]
impl HealthCheckService for DefaultHealthService {
    async fn check_database(&self) -> anyhow::Result<bool> {
        self.event_repo.ping().await.map(|_| true)
    }

    async fn check_alert_target(&self) -> anyhow::Result<bool> {
        self.alert_service
            .check_alert_target(&self.config)
            .await
            .map(|_| true)
    }
}
