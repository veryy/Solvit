import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
const pkg = JSON.parse(await readFile('../market-server/packages/dev.solvit.memory.solvit.json', 'utf8'));
const clone = value => JSON.parse(JSON.stringify(value));
function runtime(data = new Map(), rejectWrite = false) {
  const sandbox = vm.createContext({});
  vm.runInContext(pkg.source, sandbox, { timeout: 1000 });
  const ctx = { storage: {
    async get(key) { return data.has(key) ? clone(data.get(key)) : null; },
    async set(key, value) { if (rejectWrite) throw new Error('disk full'); data.set(key, clone(value)); return null; },
  } };
  return { data, run: async (name, args = {}) => clone(await sandbox.solvitPlugin.tools[name](args, ctx)) };
}
test('memory persists across engine reload and merges duplicates without losing tags', async () => {
  const first = runtime();
  const saved = await first.run('memory_put', { text: '喜欢 Kotlin', tags: ['开发'] });
  const reloaded = runtime(first.data);
  const duplicate = await reloaded.run('memory_put', { text: '喜欢 Kotlin', tags: ['偏好'] });
  assert.equal(saved.fact.id, duplicate.fact.id);
  assert.equal(duplicate.merged, true);
  assert.deepEqual(duplicate.fact.tags, ['开发', '偏好']);
  const updated = await reloaded.run('memory_put', { id: saved.fact.id, text: '喜欢 TypeScript' });
  assert.equal(updated.fact.id, saved.fact.id);
  assert.equal((await reloaded.run('memory_list')).total, 1);
});
test('batch extraction validates everything before writing', async () => {
  const plugin = runtime();
  await assert.rejects(() => plugin.run('memory_extract', { facts: [{ text: 'first' }, { text: 1 }] }), /Invalid text/);
  assert.equal((await plugin.run('memory_list')).total, 0);
  const result = await plugin.run('memory_extract', { facts: [{ text: 'first' }, { text: 'first' }, { text: 'second' }] });
  assert.equal(result.results.length, 3);
  assert.equal((await plugin.run('memory_list')).total, 2);
});
test('Chinese and English search ranks facts and profile while omitting unrelated records', async () => {
  const plugin = runtime();
  await plugin.run('memory_put', { text: '用户喜欢 Kotlin Android 开发' });
  await plugin.run('memory_put', { text: '用户不吃花生，花生过敏', category: 'health' });
  await plugin.run('memory_profile', { key: '城市', value: '上海' });
  const chinese = await plugin.run('memory_search', { query: '花生过敏' });
  assert.equal(chinese.results[0].title, 'health');
  assert.equal((await plugin.run('memory_search', { query: 'Kotlin' })).results[0].kind, 'memory');
  assert.equal((await plugin.run('memory_search', { query: '上海' })).results[0].kind, 'profile');
  assert.equal((await plugin.run('memory_search', { query: 'quantumphysics' })).results.length, 0);
});
test('graph links require existing IDs and memory deletion removes linked edges', async () => {
  const plugin = runtime();
  const a = (await plugin.run('memory_put', { text: '学习 Kotlin' })).fact.id;
  const b = (await plugin.run('memory_put', { text: '开发 Solvit' })).fact.id;
  await plugin.run('memory_link', { from: a, to: b, relation: '用于' });
  await plugin.run('memory_link', { from: a, to: b, relation: '用于' });
  assert.equal((await plugin.run('memory_graph', { id: a })).edges.length, 1);
  await assert.rejects(() => plugin.run('memory_link', { from: a, to: 'missing', relation: 'bad' }));
  await plugin.run('memory_delete', { id: b });
  assert.equal((await plugin.run('memory_graph', { id: a })).edges.length, 0);
});
test('document chunks preserve Unicode and replace old document content', async () => {
  const plugin = runtime();
  const text = '😀'.repeat(599) + 'Solvit architecture plugin design';
  const stored = await plugin.run('memory_document', { id: 'architecture', title: '架构', text });
  assert.equal(stored.chunks, 2);
  const exported = await plugin.run('memory_export');
  assert.equal(exported.chunks[0].text.includes('\uFFFD'), false);
  assert.equal((await plugin.run('memory_search', { query: 'architecture' })).results[0].kind, 'document');
  await plugin.run('memory_document', { id: 'architecture', title: 'new', text: 'different content' });
  assert.equal((await plugin.run('memory_export')).chunks.length, 1);
  assert.equal((await plugin.run('memory_search', { query: 'architecture' })).results.length, 1); // ID metadata matches.
  await plugin.run('memory_document', { id: 'architecture', remove: true });
  assert.equal((await plugin.run('memory_export')).chunks.length, 0);
});
test('profile can be deleted and blocks prototype property injection', async () => {
  const plugin = runtime();
  await plugin.run('memory_profile', { key: 'name', value: 'Solvit' });
  await plugin.run('memory_profile', { key: 'name', value: null });
  assert.deepEqual((await plugin.run('memory_export')).profile, {});
  await assert.rejects(() => plugin.run('memory_profile', { key: '__proto__', value: 'bad' }));
});
test('storage failure and malformed persisted data never silently reset user memory', async () => {
  const healthy = runtime();
  await healthy.run('memory_put', { text: 'keep me' });
  const snapshot = JSON.stringify([...healthy.data]);
  await assert.rejects(() => runtime(healthy.data, true).run('memory_put', { text: 'do not save' }), /disk full/);
  assert.equal(JSON.stringify([...healthy.data]), snapshot);
  const corrupted = new Map([['memory.v1', { format: 99 }]]);
  await assert.rejects(() => runtime(corrupted).run('memory_put', { text: 'do not reset' }), /invalid/);
  assert.deepEqual(corrupted.get('memory.v1'), { format: 99 });
});
test('oversized updates reject before changing persistent database', async () => {
  const plugin = runtime();
  for (let index = 0; index < 18; index++) await plugin.run('memory_put', { text: `${index}:` + '中'.repeat(3900) });
  const before = JSON.stringify([...plugin.data]);
  await assert.rejects(() => plugin.run('memory_put', { text: 'overflow:' + '中'.repeat(3900) }), /Memory full/);
  assert.equal(JSON.stringify([...plugin.data]), before);
});
