import { defineConfig, devices } from '@playwright/test';

// Targets a running stack (docker compose --profile apps up). Override with env vars to point elsewhere.
export const URLS = {
  order: process.env.ORDER_URL ?? 'http://localhost:8081',
  inventory: process.env.INVENTORY_URL ?? 'http://localhost:8082',
  payment: process.env.PAYMENT_URL ?? 'http://localhost:8083',
  shipping: process.env.SHIPPING_URL ?? 'http://localhost:8086',
  notification: process.env.NOTIFICATION_URL ?? 'http://localhost:8084',
  catalog: process.env.CATALOG_URL ?? 'http://localhost:8085',
  gateway: process.env.GATEWAY_URL ?? 'http://localhost:8080',
  psp: process.env.PSP_URL ?? 'http://localhost:9090',
  keycloak: process.env.KEYCLOAK_URL ?? 'http://localhost:8180',
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
  use: { baseURL: URLS.order,
    // global-setup mints an ADMIN token so the saga/contract suites can act for any generated customer;
    // the security suite overrides it per request to prove customer-level isolation
    extraHTTPHeaders: process.env.E2E_ADMIN_TOKEN ? { Authorization: `Bearer ${process.env.E2E_ADMIN_TOKEN}` } : {}, trace: 'retain-on-failure', screenshot: 'only-on-failure', video: 'retain-on-failure' },
  projects: [
    { name: 'api', testDir: './tests/api' },
    { name: 'ui', testDir: './tests/ui', use: { ...devices['Desktop Chrome'] } },
    // Opt-in suites that need a differently configured stack, so the default run stays green on the default stack:
    //   E2E_PSP=1    payment-service uses the HTTP adapter against the WireMock processor (compose profile "psp")
    //   E2E_KEYCLOAK=1  order-service validates Keycloak tokens (docker-compose.keycloak.yml overlay); the dev token endpoint is OFF
    ...(process.env.E2E_KEYCLOAK ? [{ name: 'keycloak', testDir: './tests/keycloak', use: { extraHTTPHeaders: {} } }] : []),
    //   E2E_CHAOS=1  pauses/stops real containers of the compose stack mid-flow (needs the docker CLI and the stack up)
    ...(process.env.E2E_CHAOS ? [{ name: 'chaos', testDir: './tests/chaos', workers: 1, timeout: 240_000, expect: { timeout: 90_000 } }] : []),
    ...(process.env.E2E_PSP ? [{ name: 'psp', testDir: './tests/psp', workers: 1 }] : []),
  ],
});
