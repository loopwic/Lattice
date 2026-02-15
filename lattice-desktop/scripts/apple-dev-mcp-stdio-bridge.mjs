#!/usr/bin/env node

import { spawn } from "node:child_process";

const child = spawn("apple-dev-mcp", [], {
  stdio: ["pipe", "pipe", "pipe"],
  env: process.env,
});

child.stderr.pipe(process.stderr);

let parentInputBuffer = Buffer.alloc(0);
let childOutputBuffer = "";

function parseParentFrames() {
  while (true) {
    const headerEnd = parentInputBuffer.indexOf("\r\n\r\n");
    if (headerEnd === -1) {
      return;
    }

    const header = parentInputBuffer.slice(0, headerEnd).toString("utf8");
    const lengthMatch = header.match(/content-length:\s*(\d+)/i);
    if (!lengthMatch) {
      process.stderr.write("[apple-dev-mcp-bridge] Missing Content-Length header.\n");
      process.exit(1);
    }

    const contentLength = Number.parseInt(lengthMatch[1], 10);
    const frameEnd = headerEnd + 4 + contentLength;
    if (parentInputBuffer.length < frameEnd) {
      return;
    }

    const payload = parentInputBuffer.slice(headerEnd + 4, frameEnd).toString("utf8").trim();
    parentInputBuffer = parentInputBuffer.slice(frameEnd);

    if (payload.length > 0) {
      child.stdin.write(`${payload}\n`);
    }
  }
}

function writeFramedMessage(jsonLine) {
  const body = Buffer.from(jsonLine, "utf8");
  process.stdout.write(`Content-Length: ${body.length}\r\n\r\n`);
  process.stdout.write(body);
}

process.stdin.on("data", (chunk) => {
  parentInputBuffer = Buffer.concat([parentInputBuffer, chunk]);
  parseParentFrames();
});

process.stdin.on("end", () => {
  child.stdin.end();
});

process.stdin.on("error", (error) => {
  process.stderr.write(`[apple-dev-mcp-bridge] stdin error: ${error.message}\n`);
  child.stdin.end();
});

child.stdout.on("data", (chunk) => {
  childOutputBuffer += chunk.toString("utf8");

  while (true) {
    const newLineIndex = childOutputBuffer.indexOf("\n");
    if (newLineIndex === -1) {
      break;
    }

    const line = childOutputBuffer.slice(0, newLineIndex).trim();
    childOutputBuffer = childOutputBuffer.slice(newLineIndex + 1);

    if (line.length === 0) {
      continue;
    }

    try {
      JSON.parse(line);
      writeFramedMessage(line);
    } catch {
      process.stderr.write("[apple-dev-mcp-bridge] Ignoring non-JSON stdout line.\n");
    }
  }
});

child.stdout.on("end", () => {
  const trailing = childOutputBuffer.trim();
  if (!trailing) {
    return;
  }

  try {
    JSON.parse(trailing);
    writeFramedMessage(trailing);
  } catch {
    process.stderr.write("[apple-dev-mcp-bridge] Dropping non-JSON trailing stdout data.\n");
  }
});

child.on("error", (error) => {
  process.stderr.write(`[apple-dev-mcp-bridge] Failed to start apple-dev-mcp: ${error.message}\n`);
  process.exit(1);
});

child.on("exit", (code, signal) => {
  if (signal) {
    process.exit(1);
  }

  process.exit(code ?? 0);
});
