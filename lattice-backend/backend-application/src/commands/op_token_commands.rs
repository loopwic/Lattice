use anyhow::anyhow;
use chrono::{Local, TimeZone};
use hmac::{Hmac, Mac};
use sha2::Sha256;

use crate::queries::mod_config_queries;
use crate::{AppError, AppState};
use backend_domain::{OpTokenIssueRequest, OpTokenIssueResponse};

const DEFAULT_SERVER_ID: &str = "server-01";
const TOKEN_PREFIX: &str = "lattice";
const TOKEN_VERSION: &str = "v1";

type HmacSha256 = Hmac<Sha256>;

pub async fn issue_op_token(
    state: &AppState,
    payload: OpTokenIssueRequest,
) -> Result<OpTokenIssueResponse, AppError> {
    let server_id = normalize_server_id(payload.server_id);
    let player_uuid = normalize_player_uuid(payload.player_uuid)?;
    let operator_id = normalize_required_text(payload.operator_id, "operator_id")?;
    let group_id = normalize_optional_text(payload.group_id);

    authorize_issue(&state.config, &operator_id, group_id.as_deref())?;

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
                "mod config field 'op_command_token_secret' must be a non-empty string".to_string(),
            )
        })?;

    let day = Local::now().format("%Y%m%d").to_string();
    let payload_to_sign = format!("{}|{}|{}|{}", TOKEN_PREFIX, TOKEN_VERSION, day, player_uuid);
    let signature = sign_hmac_sha256(secret, &payload_to_sign)?;
    let token = format!(
        "{}.{}.{}.{}.{}",
        TOKEN_PREFIX, TOKEN_VERSION, day, player_uuid, signature
    );

    Ok(OpTokenIssueResponse {
        token,
        day,
        player_uuid,
        expires_at: next_local_midnight_rfc3339()?,
    })
}

fn authorize_issue(
    config: &backend_domain::RuntimeConfig,
    operator_id: &str,
    group_id: Option<&str>,
) -> Result<(), AppError> {
    if is_operator_authorized(
        &config.op_token_admin_ids,
        &config.op_token_allowed_group_ids,
        operator_id,
        group_id,
    ) {
        Ok(())
    } else {
        Err(AppError::Unauthorized)
    }
}

fn is_operator_authorized(
    admin_ids: &[String],
    allowed_group_ids: &[String],
    operator_id: &str,
    group_id: Option<&str>,
) -> bool {
    let by_admin = admin_ids.iter().any(|candidate| candidate == operator_id);
    let by_group = group_id
        .map(|gid| allowed_group_ids.iter().any(|candidate| candidate == gid))
        .unwrap_or(false);
    by_admin || by_group
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
    fn normalize_player_uuid_supports_compact_format() {
        let uuid = "0123456789abcdef0123456789abcdef".to_string();
        let normalized = normalize_player_uuid(uuid).expect("normalize uuid");
        assert_eq!(normalized, "0123456789abcdef0123456789abcdef");
    }

    #[test]
    fn normalize_player_uuid_supports_canonical_format() {
        let uuid = "01234567-89ab-cdef-0123-456789abcdef".to_string();
        let normalized = normalize_player_uuid(uuid).expect("normalize uuid");
        assert_eq!(normalized, "0123456789abcdef0123456789abcdef");
    }

    #[test]
    fn normalize_player_uuid_rejects_invalid_format() {
        let err = normalize_player_uuid("invalid-value".to_string()).expect_err("reject invalid");
        match err {
            AppError::BadRequest(message) => assert!(message.contains("player_uuid")),
            _ => panic!("unexpected error type"),
        }
    }

    #[test]
    fn operator_authorization_accepts_admin_or_allowed_group() {
        let admins = vec!["admin_1".to_string()];
        let groups = vec!["group_a".to_string()];
        assert!(is_operator_authorized(
            &admins,
            &groups,
            "admin_1",
            Some("group_unknown")
        ));
        assert!(is_operator_authorized(
            &admins,
            &groups,
            "member_x",
            Some("group_a")
        ));
        assert!(!is_operator_authorized(
            &admins,
            &groups,
            "member_x",
            Some("group_b")
        ));
    }

    #[test]
    fn hmac_signature_matches_known_vector() {
        let signature = sign_hmac_sha256(
            "secret",
            "lattice|v1|20260223|0123456789abcdef0123456789abcdef",
        )
        .expect("signature");
        assert_eq!(signature.len(), 64);
        assert!(signature.chars().all(|ch| ch.is_ascii_hexdigit()));
    }
}
