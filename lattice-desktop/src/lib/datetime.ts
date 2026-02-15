export function parseTimestampMs(input: unknown): number | null {
  if (typeof input === "number" && Number.isFinite(input)) {
    if (input <= 0) {
      return null;
    }
    return input < 1_000_000_000_000 ? Math.floor(input * 1000) : Math.floor(input);
  }

  if (typeof input !== "string") {
    return null;
  }
  const raw = input.trim();
  if (!raw) {
    return null;
  }

  if (/^\d+$/.test(raw)) {
    const numeric = Number(raw);
    if (!Number.isFinite(numeric) || numeric <= 0) {
      return null;
    }
    return raw.length <= 10 ? Math.floor(numeric * 1000) : Math.floor(numeric);
  }

  let parsed = Date.parse(raw);
  if (!Number.isFinite(parsed) && raw.includes(" ")) {
    parsed = Date.parse(raw.replace(" ", "T"));
  }
  if (!Number.isFinite(parsed)) {
    return null;
  }
  return parsed;
}

const formatter = new Intl.DateTimeFormat("zh-CN", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hour12: false,
});

export function formatDateTime(input: unknown, fallback = "-") {
  const timestampMs = parseTimestampMs(input);
  if (timestampMs === null) {
    return fallback;
  }
  return formatter.format(new Date(timestampMs));
}

