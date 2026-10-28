import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests', timeout: 45_000, fullyParallel: false, workers: 1,
  use: { baseURL: process.env.TEST_BASE_URL || 'http://127.0.0.1:5173', viewport: { width: 1440, height: 1080 }, video: 'on', screenshot: 'only-on-failure', trace: 'retain-on-failure' },
  reporter: [['list']],
});
