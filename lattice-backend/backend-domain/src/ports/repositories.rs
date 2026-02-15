use async_trait::async_trait;
use std::collections::HashMap;

use crate::entities::{
    AnomalyRow,
    IngestEvent,
    ItemRegistryEntry,
    KeyItemRule,
    RconConfig,
    ReportSummary,
    StorageScanEventRow,
};

#[async_trait]
pub trait EventRepository: Send + Sync {
    async fn ensure_schema(&self) -> anyhow::Result<()>;
    async fn insert_events(&self, events: &[IngestEvent]) -> anyhow::Result<()>;
    async fn fetch_storage_scan_events(
        &self,
        date: &str,
        item: Option<&str>,
        limit: usize,
    ) -> anyhow::Result<Vec<StorageScanEventRow>>;
    async fn ping(&self) -> anyhow::Result<()>;
}

#[async_trait]
pub trait AnomalyRepository: Send + Sync {
    async fn insert_anomalies(&self, anomalies: &[AnomalyRow]) -> anyhow::Result<()>;
    async fn fetch_anomalies(
        &self,
        date: &str,
        player: Option<&str>,
    ) -> anyhow::Result<Vec<AnomalyRow>>;
    async fn fetch_summary(&self, date: &str) -> anyhow::Result<ReportSummary>;
}

#[async_trait]
pub trait ConfigRepository: Send + Sync {
    async fn load_key_items(&self, path: &str) -> anyhow::Result<HashMap<String, KeyItemRule>>;
    async fn save_key_items(&self, path: &str, rules: &[KeyItemRule]) -> anyhow::Result<()>;

    async fn load_item_registry(&self, path: &str) -> anyhow::Result<Vec<ItemRegistryEntry>>;
    async fn save_item_registry(&self, path: &str, items: &[ItemRegistryEntry]) -> anyhow::Result<()>;

    async fn load_rcon_config(&self) -> anyhow::Result<RconConfig>;
    async fn save_rcon_config(&self, config: &RconConfig) -> anyhow::Result<()>;
}
