// Event entity
// Represents item acquisition/transfer events

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IngestEvent {
    pub player_uuid: String,
    pub player_name: String,
    pub item_id: String,
    pub count: i32,
    pub event_type: String,
    pub origin: String,
    pub origin_type: String,
    pub container_location: Option<String>,
    pub timestamp: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TransferRecord {
    pub from_player: String,
    pub to_player: String,
    pub item_id: String,
    pub count: i32,
    pub timestamp: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StorageScanEventRow {
    pub date: String,
    pub player_uuid: String,
    pub player_name: String,
    pub item_id: String,
    pub count: i32,
    pub origin: String,
    pub container_location: Option<String>,
    pub timestamp: i64,
}
