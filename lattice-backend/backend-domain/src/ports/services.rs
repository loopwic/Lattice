use async_trait::async_trait;

use crate::entities::{AnomalyRow, RuntimeConfig};

#[async_trait]
pub trait AlertService: Send + Sync {
    fn spawn_alerts(&self, config: RuntimeConfig, anomalies: Vec<AnomalyRow>);
    async fn check_alert_target(&self, config: &RuntimeConfig) -> anyhow::Result<()>;
}

#[async_trait]
pub trait HealthCheckService: Send + Sync {
    async fn check_database(&self) -> anyhow::Result<bool>;
    async fn check_alert_target(&self) -> anyhow::Result<bool>;
}
