const baseUrl = process.env.SSE_BASE_URL || 'http://127.0.0.1:4000';
const rabbitUrl = process.env.SSE_RABBIT_MANAGEMENT_URL || 'http://127.0.0.1:15672';
const rabbitUser = process.env.SSE_RABBIT_USER || 'sse';
const rabbitPassword = process.env.SSE_RABBIT_PASSWORD || 'sse_dev';
const username = process.env.SSE_LOAD_USER || 'admin';
const password = process.env.SSE_LOAD_PASSWORD || 'admin@123';
const total = Number(process.env.SSE_NOTIFICATION_LOAD_COUNT || 10_000);
const concurrency = Number(process.env.SSE_NOTIFICATION_LOAD_CONCURRENCY || 100);
const timeoutMs = Number(process.env.SSE_NOTIFICATION_LOAD_TIMEOUT_MS || 300_000);
const exchange = process.env.SSE_RABBIT_EXCHANGE || 'sse.events';
const queue = process.env.SSE_RABBIT_QUEUE || 'sse.notification.events';
const dlq = process.env.SSE_RABBIT_DLQ || 'sse.notification.events.dlq';

const rabbitAuth = `Basic ${Buffer.from(`${rabbitUser}:${rabbitPassword}`).toString('base64')}`;
const encode = encodeURIComponent;

async function json(url, init = {}) {
  const response = await fetch(url, init);
  const body = await response.text();
  if (!response.ok) throw new Error(`${response.status} ${url}: ${body}`);
  return body ? JSON.parse(body) : null;
}

const login = await json(`${baseUrl}/auth/login`, {
  method: 'POST', headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ username, password, platform: 'LOAD_TEST', deviceName: 'Notification 10K probe' }),
});
const token = login.accessToken;
const userId = login.user.id;
const apiHeaders = { authorization: `Bearer ${token}` };
const before = await json(`${baseUrl}/notifications`, { headers: apiHeaders });
const beforeIds = new Set(before.map((item) => item.id));

let next = 0;
let routed = 0;
const startedAt = Date.now();
async function publisher() {
  while (next < total) {
    const index = next++;
    const event = {
      name: 'identity.user.login', actorUserId: userId, entityType: 'user', entityId: userId,
      payload: { username, role: login.user.role, loadTestIndex: index },
      occurredAt: new Date().toISOString(),
    };
    const result = await json(`${rabbitUrl}/api/exchanges/%2F/${encode(exchange)}/publish`, {
      method: 'POST',
      headers: { authorization: rabbitAuth, 'content-type': 'application/json' },
      body: JSON.stringify({
        properties: { content_type: 'application/json', headers: { __TypeId__: 'com.sse.app.event.DomainEvent' } },
        routing_key: 'identity.user.login', payload: JSON.stringify(event), payload_encoding: 'string',
      }),
    });
    if (!result.routed) throw new Error(`Event ${index} was not routed`);
    routed++;
  }
}
await Promise.all(Array.from({ length: concurrency }, publisher));

let queueState;
while (Date.now() - startedAt < timeoutMs) {
  queueState = await json(`${rabbitUrl}/api/queues/%2F/${encode(queue)}`, { headers: { authorization: rabbitAuth } });
  if (queueState.messages_ready === 0 && queueState.messages_unacknowledged === 0) break;
  await new Promise((resolve) => setTimeout(resolve, 500));
}
if (!queueState || queueState.messages_ready || queueState.messages_unacknowledged) {
  throw new Error(`Queue did not drain: ready=${queueState?.messages_ready}, unacked=${queueState?.messages_unacknowledged}`);
}

const after = await json(`${baseUrl}/notifications`, { headers: apiHeaders });
const created = after.filter((item) => !beforeIds.has(item.id));
const deadLetters = await json(`${rabbitUrl}/api/queues/%2F/${encode(dlq)}`, { headers: { authorization: rabbitAuth } });
const result = {
  published: total,
  routed,
  delivered: created.length,
  dropped: total - created.length,
  dlqMessages: deadLetters.messages,
  queueDrained: true,
  elapsedMs: Date.now() - startedAt,
  throughputPerSecond: Math.round(total * 1000 / Math.max(1, Date.now() - startedAt)),
};
console.log(JSON.stringify(result, null, 2));
if (routed !== total || created.length !== total || deadLetters.messages !== 0) process.exitCode = 1;
