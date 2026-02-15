import colors from "tailwindcss/colors";

/** @type {import('tailwindcss').Config} */
export default {
  darkMode: ["class"],
  content: [
    "./index.html",
    "./src/**/*.{ts,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        slate: colors.slate,
        emerald: colors.emerald,
        rose: colors.rose,
        amber: colors.amber,
      },
      fontFamily: {
        display: ["Sora", "IBM Plex Sans", "Noto Sans SC", "sans-serif"],
        body: ["IBM Plex Sans", "Noto Sans SC", "sans-serif"],
        mono: ["IBM Plex Mono", "ui-monospace", "SFMono-Regular", "monospace"],
      },
    },
  },
  plugins: [],
};
