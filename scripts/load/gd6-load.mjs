const baseUrl = process.env.SSE_BASE_URL || 'http://127.0.0.1:4000';
const concurrency = Number(process.env.SSE_LOAD_CONCURRENCY || 20);
const iterations = Number(process.env.SSE_LOAD_ITERATIONS || 10);

async function login() {
  const response = await fetch(`${baseUrl}/auth/login`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ username: process.env.SSE_LOAD_USER || 'admin', password: process.env.SSE_LOAD_PASSWORD || 'admin@123' }),
  });
  if (!response.ok) throw new Error(`Login failed: ${response.status}`);
  return (await response.json()).accessToken;
}

const token = await login();
const timings = [];
let failures = 0;
async function hit(path) {
  const started = performance.now();
  const response = await fetch(`${baseUrl}${path}`, { headers: { authorization: `Bearer ${token}` } });
  timings.push(performance.now() - started);
  if (!response.ok) failures++;
  await response.arrayBuffer();
}
for (let round = 0; round < iterations; round++) {
  await Promise.all(Array.from({ length: concurrency }, (_, index) => hit(index % 2 ? '/dashboard' : '/reports/academic')));
}
timings.sort((a, b) => a - b);
const percentile = (p) => timings[Math.min(timings.length - 1, Math.floor(timings.length * p))];
console.log(JSON.stringify({ requests: timings.length, failures, p50Ms: percentile(0.5), p95Ms: percentile(0.95), maxMs: timings.at(-1) }, null, 2));
if (failures) process.exitCode = 1;
