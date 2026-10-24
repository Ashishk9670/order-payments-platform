// Load test for the order saga:  k6 run load/orders.js
//
//   RATE=50 DURATION=2m k6 run load/orders.js
//
// Environment (all optional):
//   ORDER_URL      http://localhost:8081   order-service (use the gateway, http://localhost:8080, to include it)
//   CATALOG_URL    http://localhost:8085   used only in setup() to create the test product
//   INVENTORY_URL  http://localhost:8082   used only in setup() to stock it
//   RATE           orders per second at the plateau (default 20)
//   DURATION       plateau length (default 1m)
//   POLL_SAMPLE    fraction of orders whose end-to-end completion time is measured (default 0.2)
//
// The test measures what users feel (API latency AND how long until an order is actually confirmed, which includes the
// outbox relay, three Kafka hops and two database transactions) and fails the run if the targets below are missed.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const ORDER_URL = __ENV.ORDER_URL || 'http://localhost:8081';
const CATALOG_URL = __ENV.CATALOG_URL || 'http://localhost:8085';
const INVENTORY_URL = __ENV.INVENTORY_URL || 'http://localhost:8082';
const RATE = parseInt(__ENV.RATE || '20', 10);
const DURATION = __ENV.DURATION || '1m';
const POLL_SAMPLE = parseFloat(__ENV.POLL_SAMPLE || '0.2');

const confirmSeconds = new Trend('order_confirm_seconds', true);   // POST -> CONFIRMED, as the customer sees it
const confirmed = new Rate('orders_confirmed');                    // sampled orders that reached CONFIRMED in time
const placed = new Counter('orders_placed');

export const options = {
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],   // p(99) is not in k6's default set, and handleSummary reads it
  scenarios: {
    place_orders: {
      executor: 'ramping-arrival-rate',
      startRate: Math.max(1, Math.floor(RATE / 4)),
      timeUnit: '1s',
      preAllocatedVUs: Math.max(20, RATE * 2),
      maxVUs: Math.max(100, RATE * 6),
      stages: [
        { target: RATE, duration: '20s' },       // ramp up
        { target: RATE, duration: DURATION },    // plateau
        { target: 0, duration: '10s' },          // ramp down
      ],
    },
  },
  thresholds: {
    // the API must accept orders quickly and reliably...
    'http_req_failed{name:place_order}': ['rate<0.01'],
    'http_req_duration{name:place_order}': ['p(95)<500', 'p(99)<1500'],
    // ...and the asynchronous saga must keep up with the arrival rate
    order_confirm_seconds: ['p(95)<5'],
    orders_confirmed: ['rate>0.99'],
    checks: ['rate>0.99'],
    // k6 could not start iterations fast enough = the system (or the load generator) is saturated
    dropped_iterations: ['count<1'],
  },
};

export function setup() {
  const json = { headers: { 'Content-Type': 'application/json' } };

  // a short-lived admin token (dev/CI only: needs DEV_TOKEN_ENABLED=true on the order service)
  const t = http.post(`${ORDER_URL}/dev/token`, JSON.stringify({ customerId: 'k6-admin', admin: true }), json);
  if (t.status !== 200) throw new Error(`cannot get a token (${t.status}); is DEV_TOKEN_ENABLED=true?`);
  // the catalog and inventory admin APIs are internal and need that admin token
  const admin = { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${t.json('token')}` } };

  // one product with effectively unlimited stock, so the test measures throughput rather than "sold out"
  const sku = `LOAD-${Date.now()}`;
  const p = http.put(`${CATALOG_URL}/api/v1/products/${sku}`, JSON.stringify({ name: 'Load test item', price: 5 }), admin);
  const s = http.post(`${INVENTORY_URL}/api/v1/inventory/${sku}/restock`, JSON.stringify({ quantity: 10000000 }), admin);
  if (p.status >= 300 || s.status >= 300) throw new Error(`cannot create the test product (${p.status}/${s.status})`);

  return { token: t.json('token'), sku };
}

export default function (data) {
  const headers = { Authorization: `Bearer ${data.token}`, 'Content-Type': 'application/json' };
  const customerId = `load-${__VU}`;   // spread over many customers, like real traffic

  const started = Date.now();
  const res = http.post(
    `${ORDER_URL}/api/v1/orders`,
    JSON.stringify({ customerId, items: [{ sku: data.sku, quantity: 1 }] }),
    { headers, tags: { name: 'place_order' } },
  );
  const ok = check(res, { 'order accepted (202)': (r) => r.status === 202 });
  if (!ok) return;
  placed.add(1);

  if (Math.random() > POLL_SAMPLE) return;   // only a sample waits for completion, to keep the poll load realistic

  const id = res.json('id');
  for (let attempt = 0; attempt < 40; attempt++) {   // up to ~20 s
    sleep(0.5);
    const r = http.get(`${ORDER_URL}/api/v1/orders/${id}`, { headers, tags: { name: 'get_order' } });
    const status = r.status === 200 ? r.json('status') : null;
    if (status === 'CONFIRMED' || status === 'CANCELLED') {
      confirmed.add(status === 'CONFIRMED');
      confirmSeconds.add((Date.now() - started) / 1000);
      return;
    }
  }
  confirmed.add(false);   // never finished within the window
}

export function handleSummary(data) {
  const m = (name, stat) => (data.metrics[name] && data.metrics[name].values[stat]) || 0;
  const line = [
    `orders placed:        ${m('orders_placed', 'count')}`,
    `place_order p95/p99:  ${m('http_req_duration{name:place_order}', 'p(95)').toFixed(0)} / ${m('http_req_duration{name:place_order}', 'p(99)').toFixed(0)} ms`,
    `confirm p95:          ${m('order_confirm_seconds', 'p(95)').toFixed(2)} s`,
    `confirmed rate:       ${(m('orders_confirmed', 'rate') * 100).toFixed(2)} %`,
    `dropped iterations:   ${m('dropped_iterations', 'count')}`,
  ].join('\n');
  return { stdout: `\n${line}\n`, 'load/summary.json': JSON.stringify(data, null, 2) };
}
