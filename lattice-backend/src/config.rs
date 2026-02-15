use std::collections::HashMap;
use std::env;
use std::path::Path;

use anyhow::{anyhow, Result};
use serde::{Deserialize, Serialize};
use tokio::fs;
use tracing::warn;

use crate::model::KeyItemRule;

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

#[derive(Debug, Deserialize, Clone)]
#[serde(default)]
pub struct AppConfig {
    pub bind_addr: String,
    pub api_token: Option<String>,
    pub clickhouse_url: String,
    pub clickhouse_database: String,
    pub clickhouse_user: Option<String>,
    pub clickhouse_password: Option<String>,
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

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            bind_addr: "127.0.0.1:3234".to_string(),
            api_token: None,
            clickhouse_url: "http://127.0.0.1:8123".to_string(),
            clickhouse_database: "lattice".to_string(),
            clickhouse_user: None,
            clickhouse_password: None,
            report_dir: "./reports".to_string(),
            public_base_url: "http://127.0.0.1:3234".to_string(),
            webhook_url: None,
            webhook_template: None,
            alert_webhook_url: None,
            alert_webhook_template: None,
            alert_webhook_token: None,
            alert_group_id: None,
            key_items_path: "./key_items.yaml".to_string(),
            item_registry_path: "./item_registry.json".to_string(),
            transfer_window_seconds: 2,
            key_item_window_minutes: 10,
            strict_enabled: false,
            strict_pickup_window_seconds: 30,
            strict_pickup_threshold: 256,
            max_body_bytes: 8 * 1024 * 1024,
            request_timeout_seconds: 15,
            report_hour: 0,
            report_minute: 5,
        }
    }
}

impl AppConfig {
    pub async fn load() -> Result<Self> {
        let path = env::var("LATTICE_CONFIG").unwrap_or_else(|_| "./config.toml".to_string());
        let file_path = Path::new(&path);
        let base_dir = file_path.parent();
        if !file_path.exists() {
            warn!("config.toml not found, using defaults");
            let mut config = AppConfig::default();
            config.apply_env_overrides();
            config.resolve_paths(base_dir);
            config.normalize();
            config.validate()?;
            return Ok(config);
        }
        let content = fs::read_to_string(file_path).await?;
        let mut config: AppConfig = toml::from_str(&content)?;
        config.apply_env_overrides();
        config.resolve_paths(base_dir);
        config.normalize();
        config.validate()?;
        Ok(config)
    }

    pub fn normalize(&mut self) {
        if let Some(api_token) = &self.api_token {
            if api_token.trim().is_empty() {
                self.api_token = None;
            }
        }
        if let Some(user) = &self.clickhouse_user {
            if user.trim().is_empty() {
                self.clickhouse_user = None;
            }
        }
        if let Some(password) = &self.clickhouse_password {
            if password.trim().is_empty() {
                self.clickhouse_password = None;
            }
        }
        if let Some(webhook_url) = &self.webhook_url {
            if webhook_url.trim().is_empty() {
                self.webhook_url = None;
            }
        }
        if let Some(template) = &self.webhook_template {
            if template.trim().is_empty() {
                self.webhook_template = None;
            }
        }
        if let Some(alert_url) = &self.alert_webhook_url {
            if alert_url.trim().is_empty() {
                self.alert_webhook_url = None;
            }
        }
        if let Some(template) = &self.alert_webhook_template {
            if template.trim().is_empty() {
                self.alert_webhook_template = None;
            }
        }
        if let Some(token) = &self.alert_webhook_token {
            if token.trim().is_empty() {
                self.alert_webhook_token = None;
            }
        }
        if let Some(group_id) = self.alert_group_id {
            if group_id <= 0 {
                self.alert_group_id = None;
            }
        }
    }

    fn resolve_paths(&mut self, base_dir: Option<&Path>) {
        let Some(base) = base_dir else {
            return;
        };
        self.report_dir = resolve_path(base, &self.report_dir);
        self.key_items_path = resolve_path(base, &self.key_items_path);
        self.item_registry_path = resolve_path(base, &self.item_registry_path);
    }

    pub fn validate(&self) -> Result<()> {
        self.bind_addr
            .parse::<std::net::SocketAddr>()
            .map_err(|err| anyhow!("invalid bind_addr: {}", err))?;
        if self.public_base_url.trim().is_empty() {
            return Err(anyhow!("public_base_url must not be empty"));
        }
        if self.max_body_bytes == 0 {
            return Err(anyhow!("max_body_bytes must be greater than 0"));
        }
        if self.report_hour > 23 || self.report_minute > 59 {
            return Err(anyhow!("report_hour or report_minute out of range"));
        }
        Ok(())
    }

    fn apply_env_overrides(&mut self) {
        if let Ok(value) = env::var("LATTICE_BIND_ADDR") {
            self.bind_addr = value;
        }
        if let Ok(value) = env::var("LATTICE_API_TOKEN") {
            self.api_token = Some(value);
        }
        if let Ok(value) = env::var("LATTICE_CLICKHOUSE_URL") {
            self.clickhouse_url = value;
        }
        if let Ok(value) = env::var("LATTICE_CLICKHOUSE_DATABASE") {
            self.clickhouse_database = value;
        }
        if let Ok(value) = env::var("LATTICE_CLICKHOUSE_USER") {
            self.clickhouse_user = Some(value);
        }
        if let Ok(value) = env::var("LATTICE_CLICKHOUSE_PASSWORD") {
            self.clickhouse_password = Some(value);
        }
        if let Ok(value) = env::var("LATTICE_REPORT_DIR") {
            self.report_dir = value;
        }
        if let Ok(value) = env::var("LATTICE_PUBLIC_BASE_URL") {
            self.public_base_url = value;
        }
        if let Ok(value) = env::var("LATTICE_WEBHOOK_URL") {
            self.webhook_url = Some(value);
        }
        if let Ok(value) = env::var("LATTICE_WEBHOOK_TEMPLATE") {
            self.webhook_template = Some(value);
        }
        if let Ok(value) = env::var("LATTICE_ALERT_WEBHOOK_URL") {
            self.alert_webhook_url = Some(value);
        }
        if let Ok(value) = env::var("LATTICE_ALERT_WEBHOOK_TEMPLATE") {
            self.alert_webhook_template = Some(value);
        }
        if let Ok(value) = env::var("LATTICE_ALERT_WEBHOOK_TOKEN") {
            self.alert_webhook_token = Some(value);
        }
        if let Ok(value) = env::var("LATTICE_ALERT_GROUP_ID") {
            self.alert_group_id = value.parse().ok();
        }
        if let Ok(value) = env::var("LATTICE_KEY_ITEMS_PATH") {
            self.key_items_path = value;
        }
        if let Ok(value) = env::var("LATTICE_ITEM_REGISTRY_PATH") {
            self.item_registry_path = value;
        }
        if let Ok(value) = env::var("LATTICE_TRANSFER_WINDOW_SECONDS") {
            self.transfer_window_seconds = value.parse().unwrap_or(self.transfer_window_seconds);
        }
        if let Ok(value) = env::var("LATTICE_KEY_ITEM_WINDOW_MINUTES") {
            self.key_item_window_minutes = value.parse().unwrap_or(self.key_item_window_minutes);
        }
        if let Ok(value) = env::var("LATTICE_STRICT_ENABLED") {
            self.strict_enabled = value.parse().unwrap_or(self.strict_enabled);
        }
        if let Ok(value) = env::var("LATTICE_STRICT_PICKUP_WINDOW_SECONDS") {
            self.strict_pickup_window_seconds =
                value.parse().unwrap_or(self.strict_pickup_window_seconds);
        }
        if let Ok(value) = env::var("LATTICE_STRICT_PICKUP_THRESHOLD") {
            self.strict_pickup_threshold =
                value.parse().unwrap_or(self.strict_pickup_threshold);
        }
        if let Ok(value) = env::var("LATTICE_MAX_BODY_BYTES") {
            self.max_body_bytes = value.parse().unwrap_or(self.max_body_bytes);
        }
        if let Ok(value) = env::var("LATTICE_REQUEST_TIMEOUT_SECONDS") {
            self.request_timeout_seconds = value.parse().unwrap_or(self.request_timeout_seconds);
        }
        if let Ok(value) = env::var("LATTICE_REPORT_HOUR") {
            self.report_hour = value.parse().unwrap_or(self.report_hour);
        }
        if let Ok(value) = env::var("LATTICE_REPORT_MINUTE") {
            self.report_minute = value.parse().unwrap_or(self.report_minute);
        }
    }
}

fn resolve_path(base: &Path, value: &str) -> String {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return trimmed.to_string();
    }
    let path = Path::new(trimmed);
    if path.is_absolute() {
        trimmed.to_string()
    } else {
        base.join(path).to_string_lossy().to_string()
    }
}

fn resolve_config_dir() -> std::path::PathBuf {
    let path = env::var("LATTICE_CONFIG").unwrap_or_else(|_| "./config.toml".to_string());
    let file_path = Path::new(&path);
    file_path
        .parent()
        .map(|p| p.to_path_buf())
        .unwrap_or_else(|| std::path::PathBuf::from("."))
}

fn resolve_rcon_path() -> std::path::PathBuf {
    resolve_config_dir().join("rcon.toml")
}

pub async fn load_rcon_config() -> Result<RconConfig> {
    let path = resolve_rcon_path();
    if !path.exists() {
        return Ok(RconConfig::default());
    }
    let content = fs::read_to_string(&path).await?;
    let config: RconConfig = toml::from_str(&content)?;
    Ok(config)
}

pub async fn save_rcon_config(config: &RconConfig) -> Result<()> {
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

pub async fn load_key_items(path: &str) -> Result<HashMap<String, KeyItemRule>> {
    let content = fs::read_to_string(path).await?;
    let rules: Vec<KeyItemRule> = serde_yaml::from_str(&content)?;
    Ok(rules
        .into_iter()
        .map(|rule| (rule.item_id.clone(), rule))
        .collect())
}

pub async fn save_key_items(path: &str, rules: &[KeyItemRule]) -> Result<()> {
    if let Some(parent) = Path::new(path).parent() {
        if !parent.as_os_str().is_empty() {
            fs::create_dir_all(parent).await?;
        }
    }
    let content = serde_yaml::to_string(rules)?;
    fs::write(path, content).await?;
    Ok(())
}

pub async fn load_item_registry(path: &str) -> Result<Vec<crate::model::ItemRegistryEntry>> {
    if !Path::new(path).exists() {
        return Ok(Vec::new());
    }
    let content = fs::read_to_string(path).await?;
    let items: Vec<crate::model::ItemRegistryEntry> = serde_json::from_str(&content)?;
    Ok(items)
}

pub async fn save_item_registry(path: &str, items: &[crate::model::ItemRegistryEntry]) -> Result<()> {
    if let Some(parent) = Path::new(path).parent() {
        if !parent.as_os_str().is_empty() {
            fs::create_dir_all(parent).await?;
        }
    }
    let content = serde_json::to_string(items)?;
    fs::write(path, content).await?;
    Ok(())
}
