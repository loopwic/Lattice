use std::fs;
use std::fs::OpenOptions;
use std::io::Write;
use std::path::Path;
use std::path::PathBuf;
use std::sync::Mutex;
#[cfg(target_os = "macos")]
use std::time::Duration;

use lattice_backend::BackendHandle;
use rcon::Connection;
use reqwest::{Client, Url};
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager, State, WindowEvent};
use tokio::net::TcpStream;
use tokio::sync::Mutex as AsyncMutex;

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

#[derive(Serialize)]
struct TcpProbeStatus {
    target: String,
    ok: bool,
    error: Option<String>,
}

#[derive(Serialize)]
struct HttpProbeStatus {
    url: String,
    ok: bool,
    status: Option<u16>,
    body: String,
    error: Option<String>,
}

#[derive(Serialize)]
struct BackendDebugReport {
    timestamp_ms: u64,
    runtime: BackendRuntimeStatus,
    config_path: Option<String>,
    bind_addr: Option<String>,
    clickhouse_url: Option<String>,
    api_token_present: bool,
    probe_base_url: Option<String>,
    backend_tcp: TcpProbeStatus,
    clickhouse_tcp: TcpProbeStatus,
    health_live: HttpProbeStatus,
    health_ready: HttpProbeStatus,
    alert_check: HttpProbeStatus,
}

fn epoch_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|value| value.as_millis() as u64)
        .unwrap_or(0)
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
    let patch_path =
        |key: &str, target: &Path, table: &mut toml::value::Table, changed: &mut bool| match table
            .get(key)
            .and_then(|v| v.as_str())
        {
            Some(current) if !is_relative_like_path(current) => {}
            _ => {
                table.insert(key.to_string(), toml::Value::String(to_toml_path(target)));
                *changed = true;
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

fn resolve_debug_log_path(app: &AppHandle) -> Option<PathBuf> {
    app.path()
        .app_data_dir()
        .ok()
        .map(|dir| dir.join("logs").join("desktop.log"))
}

fn append_debug_log(app: &AppHandle, level: &str, message: &str) {
    let Some(path) = resolve_debug_log_path(app) else {
        return;
    };
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(mut file) = OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(file, "[{}][{}] {}", epoch_millis(), level, message);
    }
}

fn read_debug_log_tail(app: &AppHandle, lines: usize) -> String {
    let Some(path) = resolve_debug_log_path(app) else {
        return String::new();
    };
    let Ok(content) = fs::read_to_string(path) else {
        return String::new();
    };
    let max_lines = lines.clamp(50, 5000);
    let all_lines = content.lines().collect::<Vec<_>>();
    if all_lines.len() <= max_lines {
        return all_lines.join("\n");
    }
    all_lines[all_lines.len() - max_lines..].join("\n")
}

fn rcon_config_path(app: &AppHandle) -> Option<PathBuf> {
    let config_path = ensure_config(app)?;
    config_path.parent().map(|dir| dir.join("rcon.toml"))
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
    if std::env::var("LATTICE_BACKEND_DISABLE").ok().as_deref() == Some("1") {
        append_debug_log(
            app,
            "INFO",
            "backend spawn skipped by LATTICE_BACKEND_DISABLE=1",
        );
        return;
    }
    if state.handle.lock().unwrap().is_some() {
        append_debug_log(app, "INFO", "backend spawn skipped: already running");
        return;
    }
    *state.last_error.lock().unwrap() = None;

    let Some(config_path) = ensure_config(app) else {
        *state.last_error.lock().unwrap() = Some("config path unavailable".to_string());
        append_debug_log(
            app,
            "ERROR",
            "backend spawn failed: config path unavailable",
        );
        eprintln!("backend start skipped: config path unavailable");
        return;
    };
    append_debug_log(
        app,
        "INFO",
        &format!(
            "backend spawn requested with config {}",
            config_path.display()
        ),
    );

    match lattice_backend::start_embedded(config_path) {
        Ok(handle) => {
            state.handle.lock().unwrap().replace(handle);
            *state.last_error.lock().unwrap() = None;
            append_debug_log(app, "INFO", "backend spawn success");
        }
        Err(err) => {
            *state.last_error.lock().unwrap() = Some(err.to_string());
            append_debug_log(app, "ERROR", &format!("backend spawn failed: {}", err));
            eprintln!("backend start failed: {err}");
        }
    }
}

fn stop_backend(app: &AppHandle, state: &BackendState) {
    if let Some(handle) = state.handle.lock().unwrap().take() {
        append_debug_log(app, "INFO", "backend stop requested");
        handle.stop();
        append_debug_log(app, "INFO", "backend stopped");
    }
}

fn truncate_body(body: String) -> String {
    const MAX_BODY_CHARS: usize = 800;
    if body.chars().count() <= MAX_BODY_CHARS {
        return body;
    }
    let truncated = body.chars().take(MAX_BODY_CHARS).collect::<String>();
    format!("{truncated}...(truncated)")
}

fn parse_config_string(value: &toml::Value, key: &str) -> Option<String> {
    value
        .get(key)
        .and_then(|raw| raw.as_str())
        .map(|text| text.trim().to_string())
        .filter(|text| !text.is_empty())
}

fn parse_target_from_url(url_text: &str) -> Option<String> {
    let parsed = Url::parse(url_text).ok()?;
    let host = parsed.host_str()?.to_string();
    let port = parsed.port_or_known_default()?;
    Some(format!("{host}:{port}"))
}

async fn probe_tcp(target: Option<&str>, default_error: &str) -> TcpProbeStatus {
    let Some(target_text) = target
        .map(|value| value.trim())
        .filter(|value| !value.is_empty())
    else {
        return TcpProbeStatus {
            target: "-".to_string(),
            ok: false,
            error: Some(default_error.to_string()),
        };
    };

    let connect_fut = TcpStream::connect(target_text.to_string());
    match tokio::time::timeout(tokio::time::Duration::from_secs(3), connect_fut).await {
        Ok(Ok(_)) => TcpProbeStatus {
            target: target_text.to_string(),
            ok: true,
            error: None,
        },
        Ok(Err(err)) => TcpProbeStatus {
            target: target_text.to_string(),
            ok: false,
            error: Some(err.to_string()),
        },
        Err(_) => TcpProbeStatus {
            target: target_text.to_string(),
            ok: false,
            error: Some("connect timeout".to_string()),
        },
    }
}

async fn probe_http(
    client: &Client,
    url: String,
    token: Option<&str>,
    with_auth: bool,
) -> HttpProbeStatus {
    let mut request = client.get(&url);
    if with_auth {
        if let Some(value) = token.map(|v| v.trim()).filter(|v| !v.is_empty()) {
            request = request.bearer_auth(value);
        }
    }

    match request.send().await {
        Ok(response) => {
            let status = response.status();
            let body = match response.text().await {
                Ok(text) => truncate_body(text),
                Err(err) => format!("read body failed: {err}"),
            };
            HttpProbeStatus {
                url,
                ok: status.is_success(),
                status: Some(status.as_u16()),
                body,
                error: None,
            }
        }
        Err(err) => HttpProbeStatus {
            url,
            ok: false,
            status: None,
            body: String::new(),
            error: Some(err.to_string()),
        },
    }
}

fn missing_http_probe(path: &str, reason: &str) -> HttpProbeStatus {
    HttpProbeStatus {
        url: path.to_string(),
        ok: false,
        status: None,
        body: String::new(),
        error: Some(reason.to_string()),
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

#[tauri::command]
async fn backend_debug_probe(
    app: AppHandle,
    state: State<'_, BackendState>,
) -> Result<BackendDebugReport, String> {
    let runtime = BackendRuntimeStatus {
        running: state.handle.lock().unwrap().is_some(),
        last_error: state.last_error.lock().unwrap().clone(),
    };

    let config_path = ensure_config(&app).ok_or("config path unavailable".to_string())?;
    let content = fs::read_to_string(&config_path).map_err(|err| err.to_string())?;
    let parsed = content
        .parse::<toml::Value>()
        .map_err(|err| err.to_string())?;

    let bind_addr = parse_config_string(&parsed, "bind_addr");
    let clickhouse_url = parse_config_string(&parsed, "clickhouse_url");
    let public_base_url = parse_config_string(&parsed, "public_base_url");
    let api_token = parse_config_string(&parsed, "api_token");
    let api_token_present = api_token
        .as_ref()
        .map(|value| !value.trim().is_empty())
        .unwrap_or(false);

    let backend_tcp = probe_tcp(bind_addr.as_deref(), "missing bind_addr").await;
    let clickhouse_target = clickhouse_url.as_deref().and_then(parse_target_from_url);
    let clickhouse_tcp = probe_tcp(clickhouse_target.as_deref(), "missing clickhouse_url").await;

    let probe_base_url = public_base_url
        .or_else(|| bind_addr.as_ref().map(|value| format!("http://{value}")))
        .map(|value| value.trim_end_matches('/').to_string());

    let client = Client::builder()
        .no_proxy()
        .timeout(std::time::Duration::from_secs(5))
        .build()
        .map_err(|err| err.to_string())?;

    let (health_live, health_ready, alert_check) = if let Some(base_url) = &probe_base_url {
        let live = probe_http(
            &client,
            format!("{base_url}/v2/ops/health/live"),
            api_token.as_deref(),
            false,
        )
        .await;
        let ready = probe_http(
            &client,
            format!("{base_url}/v2/ops/health/ready"),
            api_token.as_deref(),
            false,
        )
        .await;
        let alert = probe_http(
            &client,
            format!("{base_url}/v2/ops/alert-target/check"),
            api_token.as_deref(),
            true,
        )
        .await;
        (live, ready, alert)
    } else {
        (
            missing_http_probe("/v2/ops/health/live", "missing probe base url"),
            missing_http_probe("/v2/ops/health/ready", "missing probe base url"),
            missing_http_probe("/v2/ops/alert-target/check", "missing probe base url"),
        )
    };

    let timestamp_ms = epoch_millis();

    append_debug_log(
        &app,
        "DEBUG",
        &format!(
            "probe result live={:?} ready={:?} alert={:?}",
            health_live.status, health_ready.status, alert_check.status
        ),
    );

    Ok(BackendDebugReport {
        timestamp_ms,
        runtime,
        config_path: Some(config_path.to_string_lossy().to_string()),
        bind_addr,
        clickhouse_url,
        api_token_present,
        probe_base_url,
        backend_tcp,
        clickhouse_tcp,
        health_live,
        health_ready,
        alert_check,
    })
}

#[tauri::command]
fn debug_log_path(app: AppHandle) -> Result<String, String> {
    let path = resolve_debug_log_path(&app).ok_or("log path unavailable".to_string())?;
    Ok(path.to_string_lossy().to_string())
}

#[tauri::command]
fn debug_log_tail(app: AppHandle, lines: Option<usize>) -> Result<String, String> {
    let limit = lines.unwrap_or(400);
    Ok(read_debug_log_tail(&app, limit))
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
    append_debug_log(
        &app,
        "INFO",
        &format!("backend config write {}", path.display()),
    );
    fs::write(&path, content).map_err(|err| err.to_string())
}

#[tauri::command]
fn backend_restart(app: AppHandle, state: State<BackendState>) -> Result<(), String> {
    append_debug_log(&app, "INFO", "backend restart requested");
    stop_backend(&app, &state);
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
async fn rcon_connect(
    app: AppHandle,
    state: State<'_, RconState>,
    config: RconConfig,
) -> Result<(), String> {
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
        .map_err(|err| {
            let message = err.to_string();
            append_debug_log(&app, "ERROR", &format!("rcon connect failed: {}", message));
            message
        })?;
    *guard = Some(conn);
    append_debug_log(&app, "INFO", "rcon connected");
    Ok(())
}

#[tauri::command]
async fn rcon_disconnect(app: AppHandle, state: State<'_, RconState>) -> Result<(), String> {
    let mut guard = state.0.lock().await;
    *guard = None;
    append_debug_log(&app, "INFO", "rcon disconnected");
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
async fn rcon_send(
    app: AppHandle,
    state: State<'_, RconState>,
    command: String,
) -> Result<String, String> {
    let mut guard = state.0.lock().await;
    let Some(conn) = guard.as_mut() else {
        return Err("RCON not connected".to_string());
    };
    let result = conn.cmd(&command).await.map_err(|err| err.to_string());
    if let Err(err) = &result {
        append_debug_log(
            &app,
            "ERROR",
            &format!("rcon send failed command={} err={}", command, err),
        );
    }
    result
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(BackendState::default())
        .manage(RconState::default())
        .setup(|app| {
            let handle = app.handle();
            let state = app.state::<BackendState>();
            append_debug_log(&handle, "INFO", "desktop setup start");
            spawn_backend(&handle, &state);
            #[cfg(target_os = "macos")]
            refresh_macos_window_shadow(&handle);
            append_debug_log(&handle, "INFO", "desktop setup done");
            Ok(())
        })
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { .. } = event {
                let app_handle = window.app_handle();
                let state = app_handle.state::<BackendState>();
                stop_backend(&app_handle, &state);
            }
        })
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            backend_config_get,
            backend_config_set,
            backend_restart,
            backend_runtime_status,
            backend_debug_probe,
            debug_log_path,
            debug_log_tail,
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
