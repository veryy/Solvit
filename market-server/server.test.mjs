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
    assert.equal(latest.plugins.find(plugin => plugin.id === 'dev.solvit.clock').version, '1.2.0');
    assert.equal(new Set(latest.plugins.map(plugin => plugin.id)).size, latest.plugins.length);
    assert.equal((await fetch(base + legacy.plugins.find(plugin => plugin.id === 'dev.solvit.clock').downloadPath)).status, 200);
    const api4 = await (await fetch(base + '/api/v1/plugins?hostApi=4')).json();
    assert.ok(api4.plugins.some(plugin => plugin.id === 'dev.solvit.workspace' && plugin.apiVersion === 4));
    assert.ok(api4.plugins.some(plugin => plugin.id === 'dev.solvit.authoring' && plugin.apiVersion === 4));
    assert.ok(latest.plugins.every(plugin => plugin.apiVersion < 4));
    const api5 = await (await fetch(base + '/api/v1/plugins?hostApi=5')).json();
    assert.ok(api5.plugins.some(plugin => plugin.id === 'dev.solvit.terminal' && plugin.apiVersion === 5));
    const api6 = await (await fetch(base + '/api/v1/plugins?hostApi=6')).json();
    assert.ok(api6.plugins.some(plugin => plugin.id === 'dev.solvit.terminal' && plugin.apiVersion === 5));
    assert.ok(api6.plugins.every(plugin => plugin.apiVersion <= 6));
    const api8 = await (await fetch(base + '/api/v1/plugins?hostApi=8')).json();
    assert.ok(api8.plugins.every(plugin => plugin.apiVersion <= 8));
    assert.equal(api8.plugins.find(plugin => plugin.id === 'dev.solvit.memory').version, '1.2.0');
    const api9 = await (await fetch(base + '/api/v1/plugins?hostApi=9')).json();
    const memoryPage = api9.plugins.find(plugin => plugin.id === 'dev.solvit.memory');
    assert.equal(memoryPage.version, '1.3.1');
    assert.equal(memoryPage.apiVersion, 9);
    assert.deepEqual(memoryPage.pages, [{id:'main',title:'记忆'},{id:'tasks',title:'自动记忆任务'}]);
    const api10 = await (await fetch(base + '/api/v1/plugins?hostApi=10')).json();
    const htmlMemory = api10.plugins.find(plugin => plugin.id === 'dev.solvit.memory');
    assert.equal(htmlMemory.apiVersion, 10); assert.equal(htmlMemory.version, '1.4.0');
    assert.ok(htmlMemory.pages.every(page => page.entry === 'ui/index.html'));
    const htmlPackage = await (await fetch(base + htmlMemory.downloadPath)).json();
    assert.ok(htmlPackage.resources['ui/index.html'].includes('id="graph"'));
    assert.ok(htmlPackage.resources['ui/main.js'].length > 100_000);
    const notesPage = api9.plugins.find(plugin => plugin.id === 'example.notes');
    assert.equal(notesPage.apiVersion, 9);
    assert.deepEqual(notesPage.pages, [{id:'main',title:'便签'}]);
    assert.ok(!api8.plugins.some(plugin => plugin.id === 'example.notes'));
    const api7 = await (await fetch(base + '/api/v1/plugins?hostApi=7')).json();
    assert.ok(api7.plugins.every(plugin => plugin.apiVersion <= 7));
    assert.ok(api7.plugins.length >= api6.plugins.length);
    assert.equal(api7.plugins.find(plugin => plugin.id === 'dev.solvit.memory').version, '1.1.0');
    assert.equal(api6.plugins.find(plugin => plugin.id === 'dev.solvit.memory').version, '1.0.0');
    assert.equal(api6.plugins.find(plugin => plugin.id === 'dev.solvit.web-search').version, '1.2.0');
    assert.equal(api5.plugins.find(plugin => plugin.id === 'dev.solvit.web-search').version, '1.1.0');
    assert.ok(api4.plugins.every(plugin => plugin.apiVersion <= 4));
    for (const plugin of [...api5.plugins, ...api7.plugins, ...api8.plugins, ...api9.plugins, ...api10.plugins]) {
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
