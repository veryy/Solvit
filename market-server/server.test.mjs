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
    const catalog = await (await fetch(base + '/api/v1/plugins')).json();
    assert.ok(catalog.plugins.length > 0);
    for (const plugin of catalog.plugins) {
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
