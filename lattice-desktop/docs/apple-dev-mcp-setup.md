# Apple Dev MCP Setup (Codex)

Date: 2026-02-14

## Why this setup exists

`apple-dev-mcp` currently speaks newline-delimited JSON on stdio, while Codex MCP uses `Content-Length` framed stdio messages.

To make them work together, this repo includes a tiny bridge:

- `scripts/apple-dev-mcp-stdio-bridge.mjs`

## Install / Reinstall

```bash
npm uninstall -g apple-hig-mcp
npm install -g apple-dev-mcp
```

## Register MCP server in Codex

Run from this repo root:

```bash
codex mcp remove apple-hig
codex mcp add apple-hig -- node /Users/khm/my-project/COBBLE/lattice-desktop/scripts/apple-dev-mcp-stdio-bridge.mjs
```

## Quick verification

1. Check config:

```bash
codex mcp get apple-hig
```

2. Validate with a simple stdio call:

- initialize
- `tools/list`
- `tools/call` using `search_unified` with `query: "button"`

Expected: successful JSON-RPC responses and search results that include Apple design/API entries.

## Notes

- `apple-dev-mcp` logs a lot to `stderr`; this is expected.
- If this repo path changes, update the absolute path in the `codex mcp add` command.
