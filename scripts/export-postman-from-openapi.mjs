import fs from 'node:fs/promises';
import path from 'node:path';

const baseUrl = (process.argv[2] || 'http://127.0.0.1:4000').replace(/\/$/, '');
const output = process.argv[3] || 'docs/postman/SSE-FULL.postman_collection.json';
const response = await fetch(`${baseUrl}/v3/api-docs`);
if (!response.ok) throw new Error(`OpenAPI returned HTTP ${response.status}`);
const spec = await response.json();

const groups = new Map();
const methods = ['get', 'post', 'put', 'patch', 'delete'];
for (const [route, pathItem] of Object.entries(spec.paths || {})) {
  for (const method of methods) {
    const operation = pathItem[method];
    if (!operation) continue;
    const tag = operation.tags?.[0] || route.split('/').filter(Boolean)[0] || 'General';
    const query = (operation.parameters || [])
      .filter((parameter) => parameter.in === 'query')
      .map((parameter) => ({ key: parameter.name, value: '', disabled: !parameter.required }));
    const rawPath = `/api/v1${route}`;
    const item = {
      name: operation.summary || operation.operationId || `${method.toUpperCase()} ${route}`,
      request: {
        method: method.toUpperCase(),
        header: operation.requestBody ? [{ key: 'Content-Type', value: 'application/json' }] : [],
        url: {
          raw: `{{baseUrl}}${rawPath}`,
          host: ['{{baseUrl}}'],
          path: rawPath.split('/').filter(Boolean),
          query,
        },
        description: operation.description || `${method.toUpperCase()} ${route}`,
      },
      response: [],
    };
    if (operation.requestBody) {
      item.request.body = { mode: 'raw', raw: '{}', options: { raw: { language: 'json' } } };
    }
    if (!groups.has(tag)) groups.set(tag, []);
    groups.get(tag).push(item);
  }
}

const collection = {
  info: {
    name: 'Smart School Ecosystem - Full API v1',
    description: 'Generated from the running Spring OpenAPI contract. Set accessToken after login.',
    schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json',
  },
  auth: { type: 'bearer', bearer: [{ key: 'token', value: '{{accessToken}}', type: 'string' }] },
  variable: [
    { key: 'baseUrl', value: baseUrl },
    { key: 'accessToken', value: '' },
  ],
  item: [...groups.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([name, item]) => ({ name, item })),
};

await fs.mkdir(path.dirname(output), { recursive: true });
await fs.writeFile(output, `${JSON.stringify(collection, null, 2)}\n`, 'utf8');
const requestCount = collection.item.reduce((total, group) => total + group.item.length, 0);
console.log(`Wrote ${requestCount} requests in ${collection.item.length} groups to ${output}`);
