use std::collections::HashMap;
use std::path::Path;

use async_trait::async_trait;
use tokio::fs;

use backend_domain::{ConfigRepository, ItemRegistryEntry, KeyItemRule, RconConfig};

pub struct ConfigFileRepository;

impl ConfigFileRepository {
    pub fn new() -> Self {
        Self
    }
}

impl Default for ConfigFileRepository {
    fn default() -> Self {
        Self::new()
    }
}

fn resolve_config_dir() -> std::path::PathBuf {
    let path = std::env::var("LATTICE_CONFIG").unwrap_or_else(|_| "./config.toml".to_string());
    let file_path = Path::new(&path);
    file_path
        .parent()
        .map(|p| p.to_path_buf())
        .unwrap_or_else(|| std::path::PathBuf::from("."))
}

fn resolve_rcon_path() -> std::path::PathBuf {
    resolve_config_dir().join("rcon.toml")
}

#[async_trait]
impl ConfigRepository for ConfigFileRepository {
    async fn load_key_items(&self, path: &str) -> anyhow::Result<HashMap<String, KeyItemRule>> {
        let content = fs::read_to_string(path).await?;
        let rules: Vec<KeyItemRule> = serde_yaml::from_str(&content)?;
        Ok(rules
            .into_iter()
            .map(|rule| (rule.item_id.clone(), rule))
            .collect())
    }

    async fn save_key_items(&self, path: &str, rules: &[KeyItemRule]) -> anyhow::Result<()> {
        if let Some(parent) = Path::new(path).parent() {
            if !parent.as_os_str().is_empty() {
                fs::create_dir_all(parent).await?;
            }
        }
        let content = serde_yaml::to_string(rules)?;
        fs::write(path, content).await?;
        Ok(())
    }

    async fn load_item_registry(&self, path: &str) -> anyhow::Result<Vec<ItemRegistryEntry>> {
        if !Path::new(path).exists() {
            return Ok(Vec::new());
        }
        let content = fs::read_to_string(path).await?;
        let items: Vec<ItemRegistryEntry> = serde_json::from_str(&content)?;
        Ok(items)
    }

    async fn save_item_registry(&self, path: &str, items: &[ItemRegistryEntry]) -> anyhow::Result<()> {
        if let Some(parent) = Path::new(path).parent() {
            if !parent.as_os_str().is_empty() {
                fs::create_dir_all(parent).await?;
            }
        }
        let content = serde_json::to_string(items)?;
        fs::write(path, content).await?;
        Ok(())
    }

    async fn load_rcon_config(&self) -> anyhow::Result<RconConfig> {
        let path = resolve_rcon_path();
        if !path.exists() {
            return Ok(RconConfig::default());
        }
        let content = fs::read_to_string(&path).await?;
        let config: RconConfig = toml::from_str(&content)?;
        Ok(config)
    }

    async fn save_rcon_config(&self, config: &RconConfig) -> anyhow::Result<()> {
        let path = resolve_rcon_path();
        if let Some(parent) = path.parent() {
            if !parent.as_os_str().is_empty() {
                fs::create_dir_all(parent).await?;
            }
        }
        let content = toml::to_string(config)?;
        fs::write(path, content).await?;
        Ok(())
    }
}
