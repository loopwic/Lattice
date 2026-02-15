import type { Settings } from "@/lib/types";

const STORAGE_KEY = "lattice_desktop_settings_v1";

export const defaultSettings: Settings = {
  baseUrl: "http://127.0.0.1:3234",
  apiToken: "",
  lang: "zh_cn",
};

export function loadSettings(): Settings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return defaultSettings;
    }
    const parsed = JSON.parse(raw) as Partial<Settings> & { apiKey?: string };
    return {
      baseUrl: parsed.baseUrl || defaultSettings.baseUrl,
      apiToken: parsed.apiToken || parsed.apiKey || "",
      lang: parsed.lang || defaultSettings.lang,
    };
  } catch {
    return defaultSettings;
  }
}

export function saveSettings(settings: Settings) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
}
