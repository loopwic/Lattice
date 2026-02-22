# Lattice OP Token Auth

This document describes the OP command token gate for player-issued OP commands.

## Behavior

- Token check is applied only to player command sources.
- When enabled, it applies to all permission-sensitive commands (all commands that require OP-level permissions).
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
lattice.v1.yyyyMMdd.playerUuidNoDash.signatureHex
```

- `yyyyMMdd`: day string in server local timezone.
- `playerUuidNoDash`: player UUID without `-`, lowercase.
- `signatureHex`: lowercase hex of:
  - HMAC-SHA256(secret = `op_command_token_secret`, payload = `lattice|v1|yyyyMMdd|playerUuidNoDash`)

## Commands

- `/lattice token apply <token>`
  - validate and grant token for current player.
- `/lattice token status`
  - show active token status / bypass status.

Guarded scope (when enabled):

- all player-issued OP commands, including vanilla/mod commands that require OP permission.
- example lattice commands: `/lattice audit`, `/lattice scan`, `/lattice registry`.

## Persistence

Grant state is persisted at:

- `<configDir>/lattice/op-token-state.json`

Expired grants are pruned automatically.
