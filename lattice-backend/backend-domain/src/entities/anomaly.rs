// Anomaly entity
// Represents a detected anomaly/suspicious activity

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnomalyRow {
    pub date: String,
    pub player_uuid: String,
    pub player_name: String,
    pub item_id: String,
    pub count: i32,
    pub origin: String,
    pub origin_type: String,
    pub risk_level: String,
    pub rule_name: String,
    pub detected_at: i64,
}
