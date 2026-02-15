use std::fs;
use std::path::Path;
use std::path::PathBuf;
use std::sync::Mutex;
#[cfg(target_os = "macos")]
use std::time::Duration;

use rcon::Connection;
use serde::{Deserialize, Serialize};
use tokio::net::TcpStream;
use tokio::sync::Mutex as AsyncMutex;
use tauri::{AppHandle, Manager, State, WindowEvent};
use lattice_backend::BackendHandle;

const DEFAULT_CONFIG_TOML_TEMPLATE: &str = r#"
bind_addr = "127.0.0.1:3234"
api_token = ""
clickhouse_url = "http://127.0.0.1:8123"
clickhouse_database = "lattice"
clickhouse_user = ""
clickhouse_password = ""
report_dir = "__REPORT_DIR__"
public_base_url = "http://127.0.0.1:3234"
webhook_url = ""
webhook_template = "{\"message\":\"{date} anomalies: high {high} medium {medium} low {low} {link}\"}"
alert_webhook_url = ""
alert_webhook_template = "{\"message\":\"rare item alert {total} lines\\n{lines}\"}"
alert_webhook_token = ""
alert_group_id = 0
key_items_path = "__KEY_ITEMS_PATH__"
item_registry_path = "__ITEM_REGISTRY_PATH__"
transfer_window_seconds = 2
key_item_window_minutes = 10
strict_enabled = true
strict_pickup_window_seconds = 30
strict_pickup_threshold = 256
max_body_bytes = 8388608
request_timeout_seconds = 15
report_hour = 0
report_minute = 5
"#;

const DEFAULT_ITEM_REGISTRY_JSON: &str = include_str!("../item_registry.json");

struct RuntimePaths {
    config_path: PathBuf,
    report_dir: PathBuf,
    key_items_path: PathBuf,
    item_registry_path: PathBuf,
}

struct BackendState {
    handle: Mutex<Option<BackendHandle>>,
    last_error: Mutex<Option<String>>,
}

impl Default for BackendState {
    fn default() -> Self {
        Self {
            handle: Mutex::new(None),
            last_error: Mutex::new(None),
        }
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(default)]
struct RconConfig {
    host: String,
    port: u16,
    password: String,
    enabled: bool,
    source: Option<String>,
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

#[derive(Default)]
struct RconState(AsyncMutex<Option<Connection<TcpStream>>>);

#[derive(Serialize)]
struct RconStatus {
    connected: bool,
}

#[derive(Serialize)]
struct BackendRuntimeStatus {
    running: bool,
    last_error: Option<String>,
}

fn default_config_path(app: &AppHandle) -> Option<PathBuf> {
    app.path()
        .app_data_dir()
        .ok()
        .map(|dir| dir.join("config.toml"))
}

fn resolve_runtime_paths(app: &AppHandle) -> Option<RuntimePaths> {
    let config_path = default_config_path(app)?;
    let app_data_dir = config_path.parent()?.to_path_buf();
    Some(RuntimePaths {
        config_path,
        report_dir: app_data_dir.join("reports"),
        key_items_path: app_data_dir.join("key_items.yaml"),
        item_registry_path: app_data_dir.join("item_registry.json"),
    })
}

fn to_toml_path(path: &Path) -> String {
    path.to_string_lossy().replace('\\', "/")
}

fn build_default_config_toml(paths: &RuntimePaths) -> String {
    DEFAULT_CONFIG_TOML_TEMPLATE
        .replace("__REPORT_DIR__", &to_toml_path(&paths.report_dir))
        .replace("__KEY_ITEMS_PATH__", &to_toml_path(&paths.key_items_path))
        .replace(
            "__ITEM_REGISTRY_PATH__",
            &to_toml_path(&paths.item_registry_path),
        )
}

fn is_relative_like_path(value: &str) -> bool {
    value.starts_with("./") || value.starts_with(".\\") || !Path::new(value).is_absolute()
}

fn patch_legacy_relative_paths(paths: &RuntimePaths) {
    let Ok(content) = fs::read_to_string(&paths.config_path) else {
        return;
    };
    let Ok(mut value) = content.parse::<toml::Value>() else {
        return;
    };
    let Some(table) = value.as_table_mut() else {
        return;
    };

    let mut changed = false;
    let patch_path = |key: &str, target: &Path, table: &mut toml::value::Table, changed: &mut bool| {
        match table.get(key).and_then(|v| v.as_str()) {
            Some(current) if !is_relative_like_path(current) => {}
            _ => {
                table.insert(key.to_string(), toml::Value::String(to_toml_path(target)));
                *changed = true;
            }
        }
    };

    patch_path("report_dir", &paths.report_dir, table, &mut changed);
    patch_path("key_items_path", &paths.key_items_path, table, &mut changed);
    patch_path(
        "item_registry_path",
        &paths.item_registry_path,
        table,
        &mut changed,
    );

    if changed {
        if let Ok(next) = toml::to_string_pretty(&value) {
            let _ = fs::write(&paths.config_path, next);
        }
    }
}

fn ensure_runtime_files(paths: &RuntimePaths) {
    let _ = fs::create_dir_all(&paths.report_dir);
    if !paths.item_registry_path.exists() {
        let _ = fs::write(&paths.item_registry_path, DEFAULT_ITEM_REGISTRY_JSON);
    }
    if !paths.key_items_path.exists() {
        let _ = fs::write(&paths.key_items_path, "rules = []\n");
    }
}

fn ensure_config(app: &AppHandle) -> Option<PathBuf> {
    let paths = resolve_runtime_paths(app)?;
    if let Some(parent) = paths.config_path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if !paths.config_path.exists() {
        let default_content = build_default_config_toml(&paths);
        let _ = fs::write(&paths.config_path, default_content.trim_start());
    } else {
        patch_legacy_relative_paths(&paths);
    }
    ensure_runtime_files(&paths);
    Some(paths.config_path)
}

fn rcon_config_path(app: &AppHandle) -> Option<PathBuf> {
    let config_path = ensure_config(app)?;
    config_path
        .parent()
        .map(|dir| dir.join("rcon.toml"))
}

fn load_rcon_config(path: &PathBuf) -> Result<RconConfig, String> {
    if !path.exists() {
        return Ok(RconConfig::default());
    }
    let content = fs::read_to_string(path).map_err(|err| err.to_string())?;
    toml::from_str(&content).map_err(|err| err.to_string())
}

fn save_rcon_config(path: &PathBuf, config: &RconConfig) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let content = toml::to_string(config).map_err(|err| err.to_string())?;
    fs::write(path, content).map_err(|err| err.to_string())
}

fn spawn_backend(app: &AppHandle, state: &BackendState) {
    if std::env::var("LATTICE_BACKEND_DISABLE")
        .ok()
        .as_deref()
        == Some("1")
    {
        return;
    }
    if state.handle.lock().unwrap().is_some() {
        return;
    }
    *state.last_error.lock().unwrap() = None;

    let Some(config_path) = ensure_config(app) else {
        *state.last_error.lock().unwrap() = Some("config path unavailable".to_string());
        eprintln!("backend start skipped: config path unavailable");
        return;
    };

    match lattice_backend::start_embedded(config_path) {
        Ok(handle) => {
            state.handle.lock().unwrap().replace(handle);
            *state.last_error.lock().unwrap() = None;
        }
        Err(err) => {
            *state.last_error.lock().unwrap() = Some(err.to_string());
            eprintln!("backend start failed: {err}");
        }
    }
}

fn stop_backend(state: &BackendState) {
    if let Some(handle) = state.handle.lock().unwrap().take() {
        handle.stop();
    }
}

#[tauri::command]
fn backend_runtime_status(state: State<BackendState>) -> BackendRuntimeStatus {
    let running = state.handle.lock().unwrap().is_some();
    let last_error = state.last_error.lock().unwrap().clone();
    BackendRuntimeStatus {
        running,
        last_error,
    }
}

#[cfg(target_os = "macos")]
fn refresh_macos_window_shadow(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        // Work around cases where shadow is not applied on first paint.
        tauri::async_runtime::spawn(async move {
            tokio::time::sleep(Duration::from_millis(80)).await;
            let _ = window.set_shadow(false);
            let _ = window.set_shadow(true);
        });
    }
}

#[tauri::command]
fn backend_config_get(app: AppHandle) -> Result<String, String> {
    let path = ensure_config(&app).ok_or("config path unavailable")?;
    fs::read_to_string(&path).map_err(|err| err.to_string())
}

#[tauri::command]
fn backend_config_set(app: AppHandle, content: String) -> Result<(), String> {
    let path = ensure_config(&app).ok_or("config path unavailable")?;
    fs::write(&path, content).map_err(|err| err.to_string())
}

#[tauri::command]
fn backend_restart(app: AppHandle, state: State<BackendState>) -> Result<(), String> {
    stop_backend(&state);
    spawn_backend(&app, &state);
    Ok(())
}

#[tauri::command]
fn rcon_config_get(app: AppHandle) -> Result<RconConfig, String> {
    let path = rcon_config_path(&app).ok_or("config path unavailable")?;
    load_rcon_config(&path)
}

#[tauri::command]
fn rcon_config_set(app: AppHandle, config: RconConfig) -> Result<(), String> {
    let path = rcon_config_path(&app).ok_or("config path unavailable")?;
    save_rcon_config(&path, &config)
}

#[tauri::command]
async fn rcon_connect(state: State<'_, RconState>, config: RconConfig) -> Result<(), String> {
    let host = if config.host.trim().is_empty() {
        "127.0.0.1".to_string()
    } else {
        config.host.trim().to_string()
    };
    let port = if config.port == 0 { 25575 } else { config.port };
    let addr = format!("{host}:{port}");
    let password = config.password;
    let mut guard = state.0.lock().await;
    let conn = Connection::builder()
        .enable_minecraft_quirks(true)
        .connect(addr, &password)
        .await
        .map_err(|err| err.to_string())?;
    *guard = Some(conn);
    Ok(())
}

#[tauri::command]
async fn rcon_disconnect(state: State<'_, RconState>) -> Result<(), String> {
    let mut guard = state.0.lock().await;
    *guard = None;
    Ok(())
}

#[tauri::command]
async fn rcon_status(state: State<'_, RconState>) -> Result<RconStatus, String> {
    let guard = state.0.lock().await;
    Ok(RconStatus {
        connected: guard.is_some(),
    })
}

#[tauri::command]
async fn rcon_send(state: State<'_, RconState>, command: String) -> Result<String, String> {
    let mut guard = state.0.lock().await;
    let Some(conn) = guard.as_mut() else {
        return Err("RCON not connected".to_string());
    };
    conn.cmd(&command).await.map_err(|err| err.to_string())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(BackendState::default())
        .manage(RconState::default())
        .setup(|app| {
            let handle = app.handle();
            let state = app.state::<BackendState>();
            spawn_backend(&handle, &state);
            #[cfg(target_os = "macos")]
            refresh_macos_window_shadow(&handle);
            Ok(())
        })
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { .. } = event {
                let state = window.app_handle().state::<BackendState>();
                stop_backend(&state);
            }
        })
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            backend_config_get,
            backend_config_set,
            backend_restart,
            backend_runtime_status,
            rcon_config_get,
            rcon_config_set,
            rcon_connect,
            rcon_disconnect,
            rcon_status,
            rcon_send
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
