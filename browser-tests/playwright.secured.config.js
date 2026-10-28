import { defineConfig } from "@playwright/test";
import { projects } from "./projects.js";
export default defineConfig({
  testDir: "./secured-tests",
  workers: 1,
  retries: 0,
  reporter: [["list"], ["html", { outputFolder: "playwright-report-secured", open: "never" }]],
  outputDir: "test-results-secured",
  use: {
    baseURL: "http://127.0.0.1:8802",
    // Network traces can contain bearer headers. Keep them out of artifacts.
    trace: "off", video: "off", screenshot: "off",
  },
  projects,
  webServer: {
    command: "python3 ../scripts/secured_browser_server.py",
    url: "http://127.0.0.1:8802/actuator/health",
    reuseExistingServer: false,
    timeout: 120000,
    gracefulShutdown: { signal: "SIGTERM", timeout: 10000 },
  },
});
