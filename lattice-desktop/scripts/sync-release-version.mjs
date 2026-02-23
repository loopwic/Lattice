import fs from "node:fs/promises";
import path from "node:path";

const version = process.argv[2]?.trim();
const semverPattern =
  /^\d+\.\d+\.\d+(?:-[0-9A-Za-z]+(?:\.[0-9A-Za-z]+)*)?(?:\+[0-9A-Za-z]+(?:\.[0-9A-Za-z]+)*)?$/;

if (!version || !semverPattern.test(version)) {
  console.error("Invalid version. Expected semantic version string.");
  process.exit(1);
}

const rootDir = process.cwd();
const tauriConfigPath = path.join(rootDir, "src-tauri", "tauri.conf.json");
const cargoTomlPath = path.join(rootDir, "src-tauri", "Cargo.toml");

const tauriConfigRaw = await fs.readFile(tauriConfigPath, "utf8");
const tauriConfig = JSON.parse(tauriConfigRaw);
tauriConfig.version = version;
await fs.writeFile(tauriConfigPath, `${JSON.stringify(tauriConfig, null, 2)}\n`, "utf8");

const cargoTomlRaw = await fs.readFile(cargoTomlPath, "utf8");
const cargoTomlNext = cargoTomlRaw.replace(
  /^version\s*=\s*"[^"]+"$/m,
  `version = "${version}"`,
);
if (cargoTomlRaw === cargoTomlNext) {
  console.error("Failed to update version in src-tauri/Cargo.toml");
  process.exit(1);
}
await fs.writeFile(cargoTomlPath, cargoTomlNext, "utf8");

console.log(`Synced desktop release version to ${version}`);
