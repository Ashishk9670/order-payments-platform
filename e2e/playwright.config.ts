import { defineConfig, devices } from '@playwright/test';

// Targets a running stack (docker compose --profile apps up). Override with env vars to point elsewhere.
export const URLS = {
  order: process.env.ORDER_URL ?? 'http://localhost:8081',
  inventory: process.env.INVENTORY_URL ?? 'http://localhost:8082',
  payment: process.env.PAYMENT_URL ?? 'http://localhost:8083',
  notification: process.env.NOTIFICATION_URL ?? 'http://localhost:8084',
};

export default defineConfig({
  testDir: './tests',
  globalSetup: './support/global-setup.ts',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: process.env.CI
    ? [['github'], ['html', { open: 'never' }], ['junit', { outputFile: 'test-results/junit.xml' }]]
    : [['list'], ['html', { open: 'never' }]],
  use: { baseURL: URLS.order, trace: 'retain-on-failure', screenshot: 'only-on-failure', video: 'retain-on-failure' },
  projects: [
    { name: 'api', testDir: './tests/api' },
    { name: 'ui', testDir: './tests/ui', use: { ...devices['Desktop Chrome'] } },
  ],
});
