import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
const pkg = JSON.parse(await readFile('../market-server/packages/dev.solvit.skills.solvit.json', 'utf8'));
const clone = value => JSON.parse(JSON.stringify(value));
const markdown = (name = 'writing', body = 'Write clear sentences.') => `---\nname: ${name}\ndescription: >\n  Help write and revise prose.\n  Use for writing tasks.\nlicense: MIT\nallowed-tools: Shell\nmetadata:\n  author: Solvit\n---\n${body}`;
async function runtime(data = new Map(), role = null, failWrite = false) {
  const sandbox = vm.createContext({});
  vm.runInContext(pkg.source, sandbox, { timeout: 1000 });
  const ctx = { role, storage: {
    async get(key) { return data.has(key) ? clone(data.get(key)) : null; },
    async set(key, value) { if (failWrite) throw new Error('disk full'); data.set(key, clone(value)); return null; },
  } };
  await sandbox.solvitPlugin.activate(ctx);
  return { data, run: async (name, args = {}) => clone(await sandbox.solvitPlugin.tools[name](args, ctx)),
    context: async () => sandbox.solvitPlugin.beforeModel({ role, userText: 'task' }, ctx) };
}
test('offline skill seeds once and deleting it survives restart', async () => {
  const first = await runtime();
  assert.equal((await first.run('skills_list')).skills[0].name, 'code-review');
  await first.run('skills_remove', { name: 'code-review' });
  const restarted = await runtime(first.data);
  assert.deepEqual((await restarted.run('skills_list')).skills, []);
  assert.equal(await restarted.context(), '');
});
test('YAML block metadata, Unicode instructions and text resources persist without execution', async () => {
  const plugin = await runtime();
  const files = { 'SKILL.md': markdown('writing', '整理中文 😀\n参考 references/guide.md'),
    'references/guide.md': 'Use concrete examples.', 'scripts/example.js': 'throw new Error("must not execute")' };
  await plugin.run('skills_import', { directory: 'writing', files });
  const restarted = await runtime(plugin.data);
  const loaded = await restarted.run('skills_load', { name: 'writing' });
  assert.match(loaded.description, /Help write and revise prose\. Use for writing tasks\./);
  assert.match(loaded.instructions, /中文 😀/);
  assert.equal(loaded.allowedTools, 'Shell');
  assert.match(loaded.notice, /不会授予/);
  assert.equal((await restarted.run('skills_read', { name: 'writing', path: 'scripts/example.js' })).text, files['scripts/example.js']);
  assert.deepEqual((await restarted.run('skills_export', { name: 'writing' })).files, files);
});
test('context contains only role-selected metadata and loads instructions on demand', async () => {
  const all = await runtime();
  await all.run('skills_import', { files: { 'SKILL.md': markdown('writing', 'PRIVATE BODY') } });
  const scoped = await runtime(all.data, { id: 'review', name: 'Review', skillIds: ['code-review'] });
  const context = await scoped.context();
  assert.match(context, /code-review/);
  assert.doesNotMatch(context, /PRIVATE BODY|writing/);
  assert.deepEqual((await scoped.run('skills_list')).skills.map(skill => skill.name), ['code-review']);
  await assert.rejects(() => scoped.run('skills_load', { name: 'writing', role: null }), /not available/);
  await assert.rejects(() => scoped.run('skills_read', { name: 'writing', path: 'references/a.md' }), /not available/);
  assert.equal(await (await runtime(all.data, { id: 'empty', name: 'Empty', skillIds: [] })).context(), '');
});
test('replacement requires explicit confirmation and writes remain atomic', async () => {
  const plugin = await runtime();
  await plugin.run('skills_import', { files: { 'SKILL.md': markdown() } });
  const before = JSON.stringify([...plugin.data]);
  await assert.rejects(() => plugin.run('skills_import', { files: { 'SKILL.md': markdown('writing', 'changed') } }), /confirm replacement/);
  assert.equal(JSON.stringify([...plugin.data]), before);
  await plugin.run('skills_import', { replace: true, files: { 'SKILL.md': markdown('writing', 'changed') } });
  assert.equal((await plugin.run('skills_load', { name: 'writing' })).instructions, 'changed');
});
test('bad names, duplicate YAML keys, aliases and directory mismatch reject before storage changes', async () => {
  const plugin = await runtime();
  const before = JSON.stringify([...plugin.data]);
  for (const text of [markdown('Bad_Name'), markdown('bad--name'), '---\nname: first\nname: second\ndescription: test\n---\nBody',
    '---\nname: &x writing\ndescription: *x\n---\nBody', 'No frontmatter']) {
    await assert.rejects(() => plugin.run('skills_import', { files: { 'SKILL.md': text } }));
  }
  await assert.rejects(() => plugin.run('skills_import', { directory: 'different', files: { 'SKILL.md': markdown() } }), /directory/);
  assert.equal(JSON.stringify([...plugin.data]), before);
});
test('resource traversal, duplicate-like unsafe paths and binary extensions reject', async () => {
  const plugin = await runtime();
  for (const path of ['../secret.md', '/references/file.md', 'references/../../secret.md', 'scripts\\evil.js', 'assets/image.png', 'references//a.md']) {
    await assert.rejects(() => plugin.run('skills_import', { files: { 'SKILL.md': markdown(), [path]: 'data' } }), /path/);
  }
  await assert.rejects(() => plugin.run('skills_import', { files: { 'SKILL.md': markdown('writing', '中'.repeat(12000)) } }), /32 KiB/);
});
test('corruption, storage failure and capacity overflow never reset existing skills', async () => {
  const first = await runtime();
  const before = JSON.stringify([...first.data]);
  await assert.rejects(() => runtime(first.data, null, true).then(plugin => plugin.run('skills_import', { files: { 'SKILL.md': markdown() } })), /disk full/);
  assert.equal(JSON.stringify([...first.data]), before);
  const corrupt = new Map([['skills.v1', { format: 99 }]]);
  await assert.rejects(() => runtime(corrupt).then(plugin => plugin.run('skills_list')), /Invalid skills/);
  assert.deepEqual(corrupt.get('skills.v1'), { format: 99 });
  for (let index = 0; index < 3; index++) await first.run('skills_import', { files: { 'SKILL.md': markdown(`long-${index}`, '中'.repeat(10000)) } });
  const full = JSON.stringify([...first.data]);
  await assert.rejects(() => first.run('skills_import', { files: { 'SKILL.md': markdown('overflow', '中'.repeat(10000)) } }), /storage full/);
  assert.equal(JSON.stringify([...first.data]), full);
});

test('maximum Unicode skill directory remains within the Android hook budget', async () => {
  const plugin = await runtime();
  await plugin.run('skills_remove', { name: 'code-review' });
  for (let index = 0; index < 32; index++) {
    const name = 'a'.repeat(59) + '-' + String(index).padStart(3, '0');
    const text = `---\nname: ${name}\ndescription: ${'中'.repeat(1024)}\n---\nBody`;
    await plugin.run('skills_import', { files: { 'SKILL.md': text } });
  }
  const context = await plugin.context();
  assert.ok(Buffer.byteLength(context) < 32 * 1024);
  assert.equal((await plugin.run('skills_list')).skills.length, 32);
});
