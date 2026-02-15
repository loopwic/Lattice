// Key item rule entity

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KeyItemRule {
    pub item_id: String,
    pub threshold: u32,
    pub risk_level: String,
}

impl KeyItemRule {
    pub fn effective_threshold(&self) -> u32 {
        if self.threshold == 0 {
            u32::MAX
        } else {
            self.threshold
        }
    }

    pub fn effective_risk_level(&self) -> &str {
        if self.risk_level.is_empty() {
            "MEDIUM"
        } else {
            &self.risk_level
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KeyItemRuleApi {
    pub item_id: String,
    pub threshold: u32,
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
