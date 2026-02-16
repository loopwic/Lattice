use clickhouse::Row;
use serde::{Deserialize, Serialize};
use time::OffsetDateTime;

#[derive(Debug, Deserialize, Serialize, Clone)]
pub struct KeyItemRule {
    pub item_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub threshold: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub max_per_10m: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub risk_level: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub weight: Option<u8>,
}

impl KeyItemRule {
    pub fn effective_threshold(&self) -> u64 {
        self.threshold.or(self.max_per_10m).unwrap_or_default()
    }

    pub fn effective_risk_level(&self) -> String {
        if let Some(level) = &self.risk_level {
            let upper = level.trim().to_uppercase();
            if upper.is_empty() {
                return "MEDIUM".to_string();
            }
            return upper;
        }
        if let Some(weight) = self.weight {
            if weight >= 8 {
                return "HIGH".to_string();
            }
        }
        "MEDIUM".to_string()
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct KeyItemRuleApi {
    pub item_id: String,
    pub threshold: u64,
    pub risk_level: String,
}

impl KeyItemRuleApi {
    pub fn normalized(&self) -> Self {
        Self {
            item_id: self.item_id.trim().to_lowercase(),
            threshold: self.threshold,
            risk_level: self.risk_level.trim().to_uppercase(),
        }
    }
}

impl From<&KeyItemRule> for KeyItemRuleApi {
    fn from(rule: &KeyItemRule) -> Self {
        Self {
            item_id: rule.item_id.clone(),
            threshold: rule.effective_threshold(),
            risk_level: rule.effective_risk_level(),
        }
    }
}

impl From<KeyItemRuleApi> for KeyItemRule {
    fn from(rule: KeyItemRuleApi) -> Self {
        Self {
            item_id: rule.item_id,
            threshold: Some(rule.threshold),
            max_per_10m: None,
            risk_level: Some(rule.risk_level),
            weight: None,
        }
    }
}

#[derive(Debug, Deserialize, Clone)]
pub struct IngestEvent {
    pub event_id: String,
    pub event_time: i64,
    pub server_id: Option<String>,
    pub event_type: String,
    pub player_uuid: Option<String>,
    pub player_name: Option<String>,
    pub item_id: String,
    pub count: i64,
    pub nbt_hash: Option<String>,
    pub origin_id: Option<String>,
    pub origin_type: Option<String>,
    pub origin_ref: Option<String>,
    pub source_type: Option<String>,
    pub source_ref: Option<String>,
    pub storage_mod: Option<String>,
    pub storage_id: Option<String>,
    pub actor_type: Option<String>,
    pub trace_id: Option<String>,
    pub item_fingerprint: Option<String>,
    pub dim: Option<String>,
    pub x: Option<i32>,
    pub y: Option<i32>,
    pub z: Option<i32>,
}

#[derive(Debug, Deserialize, Clone)]
pub struct IngestEnvelope {
    #[serde(default)]
    pub schema_version: String,
    #[serde(default)]
    pub server_id: Option<String>,
    #[serde(default)]
    pub events: Vec<IngestEvent>,
}

#[derive(Debug, Clone, Serialize, Row)]
pub struct ItemEventRow {
    #[serde(with = "clickhouse::serde::time::datetime64::millis")]
    pub event_time: OffsetDateTime,
    pub event_id: String,
    pub server_id: String,
    pub event_type: String,
    pub player_uuid: String,
    pub player_name: String,
    pub item_id: String,
    pub count: i64,
    pub origin_id: String,
    pub origin_type: String,
    pub origin_ref: String,
    pub source_type: String,
    pub source_ref: String,
    pub storage_mod: String,
    pub storage_id: String,
    pub actor_type: String,
    pub trace_id: String,
    pub item_fingerprint: String,
    pub dim: String,
    pub x: Option<i32>,
    pub y: Option<i32>,
    pub z: Option<i32>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Row)]
pub struct AnomalyRow {
    #[serde(with = "clickhouse::serde::time::datetime64::millis")]
    pub event_time: OffsetDateTime,
    pub server_id: String,
    pub player_uuid: String,
    pub player_name: String,
    pub item_id: String,
    pub count: i64,
    pub risk_level: String,
    pub rule_id: String,
    pub reason: String,
    pub evidence_json: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TransferRecord {
    pub time_ms: i64,
    pub player_uuid: String,
    pub player_name: String,
    pub item_fingerprint: String,
    pub count: i64,
    pub storage_mod: String,
    pub storage_id: String,
    pub trace_id: String,
}

#[derive(Default, Clone)]
pub struct ReportSummary {
    pub high: u64,
    pub medium: u64,
    pub low: u64,
}

#[derive(Debug, Deserialize)]
pub struct AnomalyQuery {
    pub date: Option<String>,
    pub player: Option<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ItemRegistryEntry {
    pub item_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub names: Option<std::collections::HashMap<String, String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub namespace: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct ItemRegistryPayload {
    pub items: Vec<ItemRegistryEntry>,
}

#[derive(Debug, Deserialize)]
pub struct ItemRegistryQuery {
    pub query: Option<String>,
    pub limit: Option<usize>,
    pub lang: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct ItemRegistryUpdateQuery {
    pub mode: Option<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone, Default)]
pub struct TaskProgress {
    pub running: bool,
    pub total: u64,
    pub done: u64,
    pub updated_at: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason_code: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason_message: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub targets_total_by_source: Option<TargetsTotalBySource>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub phase: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub done_by_source: Option<DoneBySource>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub trace_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub throughput_per_sec: Option<f64>,
}

#[derive(Debug, Serialize, Deserialize, Clone, Default)]
pub struct TaskStatus {
    pub audit: TaskProgress,
    pub scan: TaskProgress,
}

#[derive(Debug, Deserialize, Serialize, Clone)]
#[serde(default)]
pub struct RconConfig {
    pub host: String,
    pub port: u16,
    pub password: String,
    pub enabled: bool,
    pub source: Option<String>,
}

impl Default for RconConfig {
    fn default() -> Self {
        Self {
            host: "127.0.0.1".to_string(),
            port: 25575,
            password: String::new(),
            enabled: false,
            source: None,
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct TaskProgressUpdate {
    pub task: String,
    pub running: bool,
    pub total: u64,
    pub done: u64,
    #[serde(default)]
    pub reason_code: Option<String>,
    #[serde(default)]
    pub reason_message: Option<String>,
    #[serde(default)]
    pub targets_total_by_source: Option<TargetsTotalBySource>,
    #[serde(default)]
    pub phase: Option<String>,
    #[serde(default)]
    pub done_by_source: Option<DoneBySource>,
    #[serde(default)]
    pub trace_id: Option<String>,
    #[serde(default)]
    pub throughput_per_sec: Option<f64>,
}

#[derive(Debug, Serialize, Deserialize, Clone, Default)]
pub struct TargetsTotalBySource {
    pub world_containers: u64,
    pub sb_offline: u64,
    pub rs2_offline: u64,
    pub online_runtime: u64,
}

#[derive(Debug, Serialize, Deserialize, Clone, Default)]
pub struct DoneBySource {
    pub world_containers: u64,
    pub sb_offline: u64,
    pub rs2_offline: u64,
    pub online_runtime: u64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ModConfigEnvelope {
    pub server_id: String,
    pub revision: u64,
    pub updated_at_ms: i64,
    pub updated_by: String,
    pub checksum_sha256: String,
    pub config: serde_json::Value,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ModConfigPutRequest {
    #[serde(default)]
    pub server_id: Option<String>,
    #[serde(default)]
    pub updated_by: Option<String>,
    pub config: serde_json::Value,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ModConfigAck {
    pub server_id: String,
    pub revision: u64,
    pub status: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
    pub applied_at_ms: i64,
    #[serde(default)]
    pub changed_keys: Vec<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct AlertDeliveryRecord {
    pub timestamp_ms: i64,
    pub status: String,
    pub mode: String,
    pub attempts: u8,
    pub alert_count: usize,
    pub rule_ids: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct StorageScanQuery {
    pub date: Option<String>,
    pub item: Option<String>,
    pub limit: Option<usize>,
}

#[derive(Debug, Serialize, Deserialize, Clone, Row)]
pub struct StorageScanEventRow {
    #[serde(with = "clickhouse::serde::time::datetime64::millis")]
    pub event_time: OffsetDateTime,
    pub item_id: String,
    pub count: i64,
    pub storage_mod: String,
    pub storage_id: String,
    pub dim: String,
    pub x: Option<i32>,
    pub y: Option<i32>,
    pub z: Option<i32>,
}

#[derive(Debug, Serialize, Deserialize, Clone, Row)]
pub struct StorageScanRow {
    #[serde(with = "clickhouse::serde::time::datetime64::millis")]
    pub event_time: OffsetDateTime,
    pub item_id: String,
    pub count: i64,
    pub storage_mod: String,
    pub storage_id: String,
    pub dim: String,
    pub x: Option<i32>,
    pub y: Option<i32>,
    pub z: Option<i32>,
    pub rule_id: String,
    pub threshold: u64,
    pub risk_level: String,
    pub reason: String,
}

#[derive(Debug, Clone)]
pub struct RuntimeConfig {
    pub bind_addr: String,
    pub api_token: Option<String>,
    pub report_dir: String,
    pub public_base_url: String,
    pub webhook_url: Option<String>,
    pub webhook_template: Option<String>,
    pub alert_webhook_url: Option<String>,
    pub alert_webhook_template: Option<String>,
    pub alert_webhook_token: Option<String>,
    pub alert_group_id: Option<i64>,
    pub key_items_path: String,
    pub item_registry_path: String,
    pub transfer_window_seconds: u64,
    pub key_item_window_minutes: u64,
    pub strict_enabled: bool,
    pub strict_pickup_window_seconds: u64,
    pub strict_pickup_threshold: u64,
    pub max_body_bytes: u64,
    pub request_timeout_seconds: u64,
    pub report_hour: u32,
    pub report_minute: u32,
}

#[derive(Debug, Clone)]
pub struct DbConfig {
    pub clickhouse_url: String,
    pub clickhouse_database: String,
    pub clickhouse_user: Option<String>,
    pub clickhouse_password: Option<String>,
}
