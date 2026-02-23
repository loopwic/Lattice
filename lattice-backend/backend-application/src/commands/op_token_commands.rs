use anyhow::anyhow;
use chrono::{Local, TimeZone};
use hmac::{Hmac, Mac};
use sha2::Sha256;
use uuid::Uuid;

use crate::queries::mod_config_queries;
use crate::{AppError, AppState};
use backend_domain::{OpTokenIssueRequest, OpTokenIssueResponse, OpTokenMisuseAlertRequest};

const DEFAULT_SERVER_ID: &str = "server-01";
const TOKEN_PREFIX: &str = "lattice";
const TOKEN_VERSION: &str = "v2";

type HmacSha256 = Hmac<Sha256>;

pub async fn issue_op_token(
    state: &AppState,
    payload: OpTokenIssueRequest,
) -> Result<OpTokenIssueResponse, AppError> {
    let server_id = normalize_server_id(payload.server_id);
    let _operator_id =
        normalize_optional_text(payload.operator_id).unwrap_or_else(|| "unknown".to_string());
    let group_id = normalize_optional_text(payload.group_id);

    authorize_issue(&state.config, group_id.as_deref())?;

    let envelope = mod_config_queries::get_mod_config(state, &server_id).await?;
    let envelope = envelope.ok_or_else(|| {
        AppError::BadRequest(format!("mod config not found for server '{}'", server_id))
    })?;

    let token_required = envelope
        .config
        .get("op_command_token_required")
        .and_then(|value| value.as_bool())
        .ok_or_else(|| {
            AppError::BadRequest(
                "mod config field 'op_command_token_required' must be boolean".to_string(),
            )
        })?;
    if !token_required {
        return Err(AppError::BadRequest(format!(
            "op_command_token_required is disabled for server '{}'",
            server_id
        )));
    }

    let secret = envelope
        .config
        .get("op_command_token_secret")
        .and_then(|value| value.as_str())
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .ok_or_else(|| {
            AppError::BadRequest(
                "mod config field 'op_command_token_secret' must be a non-empty string"
                    .to_string(),
            )
        })?;

    let day = Local::now().format("%Y%m%d").to_string();
    let token_id = Uuid::new_v4().simple().to_string();
    let payload_to_sign = format!("{}|{}|{}|{}", TOKEN_PREFIX, TOKEN_VERSION, day, token_id);
    let signature = sign_hmac_sha256(secret, &payload_to_sign)?;
    let token = format!(
        "{}.{}.{}.{}.{}",
        TOKEN_PREFIX, TOKEN_VERSION, day, token_id, signature
    );

    Ok(OpTokenIssueResponse {
        token,
        day,
        expires_at: next_local_midnight_rfc3339()?,
    })
}

pub fn build_issue_success_message(issued: &OpTokenIssueResponse) -> String {
    format!(
        "[Lattice OP Token]\n复制执行: /lattice token apply {}\nToken: {}\n有效期至: {}\n绑定规则: 首次 apply 自动绑定账号，跨账号复用会作废并告警",
        issued.token, issued.token, issued.expires_at
    )
}

pub fn build_issue_failure_message(err: &AppError) -> String {
    match err {
        AppError::Unauthorized => {
            "申请失败：当前群未授权，请联系管理员配置 op_token_allowed_group_ids".to_string()
        }
        AppError::BadRequest(message) => format!("申请失败：{}", message),
        AppError::Internal(_) => "申请失败：后端内部错误".to_string(),
    }
}

pub async fn report_op_token_misuse(
    state: &AppState,
    payload: OpTokenMisuseAlertRequest,
) -> Result<(), AppError> {
    let server_id = normalize_server_id(payload.server_id);
    let attempt_player_uuid = normalize_player_uuid(payload.attempt_player_uuid)?;
    let token_owner_uuid = normalize_player_uuid(payload.token_owner_uuid)?;
    let attempt_player_name =
        normalize_required_text(payload.attempt_player_name, "attempt_player_name")?;
    let message = format!(
        "OP Token 安全告警: 玩家 {}({}) 试图使用属于 {} 的 token，token 已作废。server={}",
        attempt_player_name, attempt_player_uuid, token_owner_uuid, server_id
    );
    state
        .alert_service
        .send_system_alert(&state.config, &message)
        .await
        .map_err(|err| AppError::Internal(err.into()))
}

fn authorize_issue(config: &backend_domain::RuntimeConfig, group_id: Option<&str>) -> Result<(), AppError> {
    let gid = group_id
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .ok_or_else(|| AppError::BadRequest("group_id is required".to_string()))?;
    if is_group_authorized(&config.op_token_allowed_group_ids, gid) {
        return Ok(());
    }
    Err(AppError::Unauthorized)
}

fn is_group_authorized(allowed_group_ids: &[String], group_id: &str) -> bool {
    allowed_group_ids.iter().any(|candidate| candidate == group_id)
}

fn normalize_server_id(value: Option<String>) -> String {
    normalize_optional_text(value)
        .map(|item| item.to_lowercase())
        .unwrap_or_else(|| DEFAULT_SERVER_ID.to_string())
}

fn normalize_required_text(value: String, field: &str) -> Result<String, AppError> {
    normalize_optional_text(Some(value))
        .ok_or_else(|| AppError::BadRequest(format!("{} must not be empty", field)))
}

fn normalize_optional_text(value: Option<String>) -> Option<String> {
    value.and_then(|raw| {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            None
        } else {
            Some(trimmed.to_string())
        }
    })
}

fn normalize_player_uuid(raw_uuid: String) -> Result<String, AppError> {
    let raw = raw_uuid.trim().to_lowercase();
    if raw.is_empty() {
        return Err(AppError::BadRequest(
            "player_uuid must not be empty".to_string(),
        ));
    }
    if raw.len() == 32 {
        if raw.chars().all(|ch| ch.is_ascii_hexdigit()) {
            return Ok(raw);
        }
        return Err(AppError::BadRequest(
            "player_uuid(32) must be lowercase hex".to_string(),
        ));
    }
    if raw.len() == 36 {
        if is_canonical_uuid_36(&raw) {
            return Ok(raw.replace('-', ""));
        }
        return Err(AppError::BadRequest(
            "player_uuid(36) must be canonical uuid".to_string(),
        ));
    }
    Err(AppError::BadRequest(
        "player_uuid must be 32-char hex or 36-char uuid".to_string(),
    ))
}

fn is_canonical_uuid_36(value: &str) -> bool {
    for (idx, ch) in value.chars().enumerate() {
        if idx == 8 || idx == 13 || idx == 18 || idx == 23 {
            if ch != '-' {
                return false;
            }
            continue;
        }
        if !ch.is_ascii_hexdigit() {
            return false;
        }
    }
    true
}

fn sign_hmac_sha256(secret: &str, payload: &str) -> Result<String, AppError> {
    let mut mac = HmacSha256::new_from_slice(secret.as_bytes())
        .map_err(|err| AppError::Internal(anyhow!("hmac init failed: {err}")))?;
    mac.update(payload.as_bytes());
    let digest = mac.finalize().into_bytes();

    let mut out = String::with_capacity(digest.len() * 2);
    for byte in digest {
        out.push_str(&format!("{byte:02x}"));
    }
    Ok(out)
}

fn next_local_midnight_rfc3339() -> Result<String, AppError> {
    let now = Local::now();
    let next_day = now
        .date_naive()
        .succ_opt()
        .ok_or_else(|| AppError::Internal(anyhow!("failed to calculate next day")))?;
    let next_midnight = next_day
        .and_hms_opt(0, 0, 0)
        .ok_or_else(|| AppError::Internal(anyhow!("failed to calculate next midnight")))?;
    let local_time = Local
        .from_local_datetime(&next_midnight)
        .single()
        .or_else(|| Local.from_local_datetime(&next_midnight).earliest())
        .ok_or_else(|| {
            AppError::Internal(anyhow!(
                "failed to resolve local timezone for next midnight"
            ))
        })?;
    Ok(local_time.to_rfc3339())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn group_authorization_accepts_only_allowed_group() {
        let groups = vec!["group_a".to_string()];
        assert!(is_group_authorized(&groups, "group_a"));
        assert!(!is_group_authorized(&groups, "group_b"));
    }

    #[test]
    fn authorize_issue_requires_group_id() {
        let config = backend_domain::RuntimeConfig {
            bind_addr: "127.0.0.1:3234".to_string(),
            api_token: None,
            op_token_admin_ids: vec!["admin_1".to_string()],
            op_token_allowed_group_ids: vec!["group_a".to_string()],
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
            max_body_bytes: 1024,
            request_timeout_seconds: 15,
            report_hour: 0,
            report_minute: 5,
        };

        let result_missing = authorize_issue(&config, None);
        match result_missing {
            Err(AppError::BadRequest(message)) => assert!(message.contains("group_id")),
            _ => panic!("unexpected result"),
        }

        let result_allowed = authorize_issue(&config, Some("group_a"));
        assert!(result_allowed.is_ok());

        let result_denied = authorize_issue(&config, Some("group_b"));
        match result_denied {
            Err(AppError::Unauthorized) => {}
            _ => panic!("unexpected result"),
        }
    }

    #[test]
    fn normalize_player_uuid_supports_compact_uuid() {
        let normalized =
            normalize_player_uuid("0123456789abcdef0123456789abcdef".to_string()).expect("uuid");
        assert_eq!(normalized, "0123456789abcdef0123456789abcdef");
    }

    #[test]
    fn normalize_player_uuid_supports_canonical_uuid() {
        let normalized =
            normalize_player_uuid("01234567-89ab-cdef-0123-456789abcdef".to_string()).expect("uuid");
        assert_eq!(normalized, "0123456789abcdef0123456789abcdef");
    }

    #[test]
    fn normalize_player_uuid_rejects_invalid_format() {
        let err = normalize_player_uuid("invalid-value".to_string()).expect_err("reject invalid");
        match err {
            AppError::BadRequest(message) => assert!(message.contains("player_uuid")),
            _ => panic!("unexpected error"),
        }
    }

    #[test]
    fn hmac_signature_matches_known_vector() {
        let signature =
            sign_hmac_sha256("secret", "lattice|v2|20260223|0123456789abcdef0123456789abcdef")
                .expect("signature");
        assert_eq!(signature.len(), 64);
        assert!(signature.chars().all(|ch| ch.is_ascii_hexdigit()));
    }
}
