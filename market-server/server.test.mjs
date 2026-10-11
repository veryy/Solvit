import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createMarketServer } from './server.mjs';

test('catalog downloads match their declared integrity and expose no write API', async () => {
  const server = createMarketServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const health = await (await fetch(base + '/health')).json();
    assert.equal(health.status, 'ok');
    const legacy = await (await fetch(base + '/api/v1/plugins')).json();
    assert.ok(legacy.plugins.every(plugin => plugin.apiVersion === 1));
    assert.equal(legacy.plugins.find(plugin => plugin.id === 'dev.solvit.clock').version, '1.0.0');
    const catalog = await (await fetch(base + '/api/v1/plugins?hostApi=2')).json();
    assert.ok(catalog.plugins.length > 0);
    assert.ok(catalog.plugins.every(plugin => plugin.apiVersion <= 2));
    const latest = await (await fetch(base + '/api/v1/plugins?hostApi=3')).json();
    assert.ok(latest.plugins.some(plugin => plugin.id === 'dev.solvit.skills' && plugin.apiVersion === 3));
    assert.equal(latest.plugins.find(plugin => plugin.id === 'dev.solvit.clock').version, '1.1.0');
    assert.equal(new Set(latest.plugins.map(plugin => plugin.id)).size, latest.plugins.length);
    assert.equal((await fetch(base + legacy.plugins.find(plugin => plugin.id === 'dev.solvit.clock').downloadPath)).status, 200);
    for (const plugin of latest.plugins) {
      const response = await fetch(base + plugin.downloadPath);
      assert.equal(response.status, 200);
      const bytes = Buffer.from(await response.arrayBuffer());
      assert.equal(bytes.length, plugin.bytes);
      assert.equal(createHash('sha256').update(bytes).digest('hex'), plugin.sha256);
      assert.equal(JSON.parse(bytes).manifest.id, plugin.id);
    }
    assert.equal((await fetch(base + '/api/v1/plugins', { method: 'POST', body: '{}' })).status, 405);
    assert.equal((await fetch(base + '/api/v1/packages/unknown.plugin/1.0.0')).status, 404);
    assert.equal((await fetch(base + '/api/v1/packages/../../server.mjs')).status, 404);
  } finally { await new Promise(resolve => server.close(resolve)); }
});

test('MCP demo negotiates read-only tools and rejects unknown calls and origins', async () => {
  const server = createMarketServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const endpoint = `http://127.0.0.1:${server.address().port}/mcp-demo`;
  let id = 0;
  const rpc = async (method, params) => (await fetch(endpoint, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({jsonrpc:'2.0',id:++id,method,params}) })).json();
  try {
    assert.equal((await rpc('initialize',{protocolVersion:'2025-11-25'})).result.protocolVersion,'2025-11-25');
    assert.equal((await rpc('tools/list',{})).result.tools.length,2);
    assert.equal((await rpc('tools/call',{name:'echo',arguments:{text:'你好'}})).result.content[0].text,'你好');
    assert.equal((await rpc('tools/call',{name:'shell',arguments:{}})).error.code,-32602);
    assert.equal((await fetch(endpoint,{method:'POST',headers:{Origin:'https://untrusted.example'}})).status,403);
    assert.equal((await fetch(endpoint)).status,405);
  } finally { await new Promise(resolve => server.close(resolve)); }
});
