# Lattice OP Token Auth

This document describes the OP command token gate for player-issued OP commands.

## Behavior

- Token check is applied only to player command sources.
- When enabled, it applies to all permission-sensitive commands that require OP level 2 or higher.
- Non-player sources bypass token check:
  - server console
  - backend/RCON direct command execution
  - command block and other non-player automation sources
- This means backend direct execution and FTB non-player automation are not affected.
- `/lattice token ...` is exempt from token gating so players can always apply/check token.
- Player token grant expires automatically at next local day boundary (`00:00`, server system timezone).

## Config (lattice.toml)

```toml
op_command_token_required = false
op_command_token_secret = ""
```

- `op_command_token_required`: global switch; when `true`, player-issued OP commands require a valid token.
- `op_command_token_secret`: shared secret used by robot and server to sign/verify daily tokens.

## Token Format

```text
lattice.v2.yyyyMMdd.tokenId.signatureHex
```

- `yyyyMMdd`: day string in server local timezone.
- `tokenId`: 32-char lowercase hex token identifier.
- `signatureHex`: lowercase hex of:
  - HMAC-SHA256(secret = `op_command_token_secret`, payload = `lattice|v2|yyyyMMdd|tokenId`)
- token is unbound when issued.
- first successful `/lattice token apply <token>` binds token to current player UUID.
- if a different UUID tries to apply the same token:
  - token is revoked immediately (for all accounts) until day rollover
  - owner's active grant from that token is removed
  - backend group warning is emitted through webhook

## Commands

- `/lattice token apply <token>`
  - validate and grant token for current player.
- `/lattice token status`
  - show active token status / bypass status.

Guarded scope (when enabled):

- all player-issued commands requiring OP level 2 or higher, including vanilla/mod commands.
- example lattice commands: `/lattice audit`, `/lattice scan`, `/lattice registry`.

## Persistence

Grant state is persisted at:

- `<configDir>/lattice/op-token-state.json`

Expired grants are pruned automatically.

## Robot Issuance Flow

Recommended production flow is "robot requests backend issuance":

1. Group member sends apply command to robot (for example `/申请`).
2. Robot calls backend API:
   - `POST /v2/ops/op-token/issue`
   - body includes `group_id`, and optional `operator_id`.
3. Robot returns issued token to the requester.
4. Player runs `/lattice token apply <token>` in game.
5. When misuse is detected, mod calls:
   - `POST /v2/ops/op-token/misuse-alert`
   - backend forwards warning message to configured group webhook.

Backend-side allowlist controls who can issue:

- `group_id` must exist in `op_token_allowed_group_ids = [...]`.

Issuance uses the server's mod-config values:

- `op_command_token_required = true`
- `op_command_token_secret` must be non-empty
