use std::collections::HashMap;
use std::path::Path;

use async_trait::async_trait;
use tokio::fs;

use backend_domain::{
    ConfigRepository,
    ItemRegistryEntry,
    KeyItemRule,
    ModConfigAck,
    ModConfigEnvelope,
    RconConfig,
};

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

fn sanitize_server_id(server_id: &str) -> String {
    let mut value = server_id.trim().to_lowercase();
    if value.is_empty() {
        value = "default".to_string();
    }
    value
        .chars()
        .map(|ch| {
            if ch.is_ascii_alphanumeric() || ch == '-' || ch == '_' {
                ch
            } else {
                '_'
            }
        })
        .collect()
}

fn resolve_mod_config_dir() -> std::path::PathBuf {
    resolve_config_dir().join("mod-config")
}

fn resolve_mod_config_path(server_id: &str) -> std::path::PathBuf {
    resolve_mod_config_dir().join(format!("{}.json", sanitize_server_id(server_id)))
}

fn resolve_mod_config_ack_path(server_id: &str) -> std::path::PathBuf {
    resolve_mod_config_dir()
        .join("acks")
        .join(format!("{}.json", sanitize_server_id(server_id)))
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

    async fn load_mod_config(&self, server_id: &str) -> anyhow::Result<Option<ModConfigEnvelope>> {
        let path = resolve_mod_config_path(server_id);
        if !path.exists() {
            return Ok(None);
        }
        let content = fs::read_to_string(&path).await?;
        let envelope: ModConfigEnvelope = serde_json::from_str(&content)?;
        Ok(Some(envelope))
    }

    async fn save_mod_config(&self, envelope: &ModConfigEnvelope) -> anyhow::Result<()> {
        let path = resolve_mod_config_path(&envelope.server_id);
        if let Some(parent) = path.parent() {
            if !parent.as_os_str().is_empty() {
                fs::create_dir_all(parent).await?;
            }
        }
        let content = serde_json::to_string(envelope)?;
        fs::write(path, content).await?;
        Ok(())
    }

    async fn load_mod_config_ack(&self, server_id: &str) -> anyhow::Result<Option<ModConfigAck>> {
        let path = resolve_mod_config_ack_path(server_id);
        if !path.exists() {
            return Ok(None);
        }
        let content = fs::read_to_string(&path).await?;
        let ack: ModConfigAck = serde_json::from_str(&content)?;
        Ok(Some(ack))
    }

    async fn save_mod_config_ack(&self, ack: &ModConfigAck) -> anyhow::Result<()> {
        let path = resolve_mod_config_ack_path(&ack.server_id);
        if let Some(parent) = path.parent() {
            if !parent.as_os_str().is_empty() {
                fs::create_dir_all(parent).await?;
            }
        }
        let content = serde_json::to_string(ack)?;
        fs::write(path, content).await?;
        Ok(())
    }
}
