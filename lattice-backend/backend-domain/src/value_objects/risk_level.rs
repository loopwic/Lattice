// Risk level value object

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
}

impl RiskLevel {
    pub fn as_str(&self) -> &'static str {
        match self {
            RiskLevel::LOW => "LOW",
            RiskLevel::MEDIUM => "MEDIUM",
            RiskLevel::HIGH => "HIGH",
        }
    }
}

impl From<&str> for RiskLevel {
    fn from(s: &str) -> Self {
        match s.to_uppercase().as_str() {
            "LOW" => RiskLevel::LOW,
            "HIGH" => RiskLevel::HIGH,
            _ => RiskLevel::MEDIUM,
        }
    }
}
