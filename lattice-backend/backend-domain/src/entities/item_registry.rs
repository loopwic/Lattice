// Item registry entity

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ItemRegistryEntry {
    pub item_id: String,
    pub namespace: Option<String>,
    pub path: Option<String>,
    pub name: Option<String>,
    pub names: Option<HashMap<String, String>>,
}
