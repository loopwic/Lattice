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
