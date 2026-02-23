use std::env;
use std::path::Path;

use anyhow::{anyhow, Result};
use serde::Deserialize;
use tokio::fs;
use tracing::warn;

use backend_domain::{DbConfig, RuntimeConfig};

#[derive(Debug, Deserialize, Clone)]
#[serde(default)]
pub struct AppConfig {
    pub bind_addr: String,
    pub api_token: Option<String>,
    pub op_token_admin_ids: Vec<String>,
    pub op_token_allowed_group_ids: Vec<String>,
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
            op_token_admin_ids: Vec::new(),
            op_token_allowed_group_ids: Vec::new(),
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
        self.op_token_admin_ids = normalize_id_list(std::mem::take(&mut self.op_token_admin_ids));
        self.op_token_allowed_group_ids =
            normalize_id_list(std::mem::take(&mut self.op_token_allowed_group_ids));
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

    pub fn to_runtime_config(&self) -> RuntimeConfig {
        RuntimeConfig {
            bind_addr: self.bind_addr.clone(),
            api_token: self.api_token.clone(),
            op_token_admin_ids: self.op_token_admin_ids.clone(),
            op_token_allowed_group_ids: self.op_token_allowed_group_ids.clone(),
            report_dir: self.report_dir.clone(),
            public_base_url: self.public_base_url.clone(),
            webhook_url: self.webhook_url.clone(),
            webhook_template: self.webhook_template.clone(),
            alert_webhook_url: self.alert_webhook_url.clone(),
            alert_webhook_template: self.alert_webhook_template.clone(),
            alert_webhook_token: self.alert_webhook_token.clone(),
            alert_group_id: self.alert_group_id,
            key_items_path: self.key_items_path.clone(),
            item_registry_path: self.item_registry_path.clone(),
            transfer_window_seconds: self.transfer_window_seconds,
            key_item_window_minutes: self.key_item_window_minutes,
            strict_enabled: self.strict_enabled,
            strict_pickup_window_seconds: self.strict_pickup_window_seconds,
            strict_pickup_threshold: self.strict_pickup_threshold,
            max_body_bytes: self.max_body_bytes,
            request_timeout_seconds: self.request_timeout_seconds,
            report_hour: self.report_hour,
            report_minute: self.report_minute,
        }
    }

    pub fn to_db_config(&self) -> DbConfig {
        DbConfig {
            clickhouse_url: self.clickhouse_url.clone(),
            clickhouse_database: self.clickhouse_database.clone(),
            clickhouse_user: self.clickhouse_user.clone(),
            clickhouse_password: self.clickhouse_password.clone(),
        }
    }

    fn apply_env_overrides(&mut self) {
        if let Ok(value) = env::var("LATTICE_BIND_ADDR") {
            self.bind_addr = value;
        }
        if let Ok(value) = env::var("LATTICE_API_TOKEN") {
            self.api_token = Some(value);
        }
        if let Ok(value) = env::var("LATTICE_OP_TOKEN_ADMIN_IDS") {
            self.op_token_admin_ids = parse_env_id_list(&value);
        }
        if let Ok(value) = env::var("LATTICE_OP_TOKEN_ALLOWED_GROUP_IDS") {
            self.op_token_allowed_group_ids = parse_env_id_list(&value);
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
            self.strict_pickup_threshold = value.parse().unwrap_or(self.strict_pickup_threshold);
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

fn parse_env_id_list(value: &str) -> Vec<String> {
    value
        .split(',')
        .map(|item| item.trim())
        .filter(|item| !item.is_empty())
        .map(ToString::to_string)
        .collect()
}

fn normalize_id_list(values: Vec<String>) -> Vec<String> {
    let mut out: Vec<String> = values
        .into_iter()
        .map(|item| item.trim().to_string())
        .filter(|item| !item.is_empty())
        .collect();
    out.sort();
    out.dedup();
    out
}
