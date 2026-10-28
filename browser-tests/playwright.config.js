import { defineConfig } from "@playwright/test";
import { projects } from "./projects.js";

export default defineConfig({
  testDir: "./tests",
  workers: 1,
  retries: 0,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: "http://127.0.0.1:8801",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects,
  webServer: {
    command: "python3 ../scripts/browser_server.py",
    url: "http://127.0.0.1:8801/actuator/health",
    reuseExistingServer: false,
    timeout: 120000,
    gracefulShutdown: { signal: "SIGTERM", timeout: 10000 },
  },
});
