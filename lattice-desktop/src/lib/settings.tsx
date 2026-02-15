import React from "react";
import type { Settings } from "@/lib/types";
import { defaultSettings, loadSettings, saveSettings } from "@/lib/storage";

const SettingsContext = React.createContext<{
  settings: Settings;
  updateSettings: (next: Settings) => void;
} | null>(null);

export function SettingsProvider({ children }: { children: React.ReactNode }) {
  const [settings, setSettings] = React.useState<Settings>(() => loadSettings());

  const updateSettings = React.useCallback((next: Settings) => {
    setSettings(next);
    saveSettings(next);
  }, []);

  return (
    <SettingsContext.Provider value={{ settings, updateSettings }}>
      {children}
    </SettingsContext.Provider>
  );
}

export function useSettings() {
  const ctx = React.useContext(SettingsContext);
  if (!ctx) {
    return { settings: defaultSettings, updateSettings: () => undefined };
  }
  return ctx;
}
