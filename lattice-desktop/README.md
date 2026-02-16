# Lattice

Cross-platform desktop console for Lattice backend (macOS + Windows).

## Prerequisites

- Node.js 18+
- Rust toolchain
- Tauri system dependencies

## Development

```bash
cd lattice-desktop
npm install
npm run tauri dev
```

## Build

```bash
cd lattice-desktop
npm run tauri build
```

## Notes

- The desktop app embeds the backend runtime and starts it automatically.
- A default config file is written to the app data directory on first run.
- You can edit the backend config inside the app (配置页) and restart it to apply changes.
- The UI assumes the backend is listening on `http://127.0.0.1:3234` unless you change the config.

## Dynamic Mod Config

System page includes a dedicated **Mod 动态配置** panel:

- load current mod config by `server_id`
- edit fixed keys in friendly form fields (not raw JSON)
- publish new revision to backend
- inspect last mod apply ack (`APPLIED` / `PARTIAL` / `REJECTED`)

### Quick Troubleshooting

1. Publish config and verify revision increments.
2. Wait for mod ack refresh (`~5-15s` with WS + pull fallback).
3. If ack is `REJECTED`, check ack message and mod `logs/lattice-ops.log`.
4. If no ack appears, verify mod `backend_url` / `api_token` and backend auth settings.
