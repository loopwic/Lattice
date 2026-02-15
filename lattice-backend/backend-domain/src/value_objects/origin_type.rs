// Origin type value object

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum OriginType {
    Crafting,
    Smelting,
    Trading,
    Mining,
    Fishing,
    Looting,
    Breeding,
    Transfer,
    Unknown,
}

impl From<&str> for OriginType {
    fn from(s: &str) -> Self {
        match s.to_lowercase().as_str() {
            "crafting" => OriginType::Crafting,
            "smelting" => OriginType::Smelting,
            "trading" => OriginType::Trading,
            "mining" => OriginType::Mining,
            "fishing" => OriginType::Fishing,
            "looting" => OriginType::Looting,
            "breeding" => OriginType::Breeding,
            "transfer" => OriginType::Transfer,
            _ => OriginType::Unknown,
        }
    }
}
