import { defineConfig } from '@playwright/test';

const browser = process.env['CHROMIUM_PATH'];

export default defineConfig({
  testDir: 'e2e',
  timeout: 60_000,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: process.env['WEB_URL'] ?? 'http://localhost:4173',
    screenshot: 'only-on-failure',
    launchOptions: browser ? { executablePath: browser } : {},
  },
});
