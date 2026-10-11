import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import vm from 'node:vm';
import { buildPlugin } from '../scripts/build-plugin.mjs';

test('bundles TypeScript imports as standalone host-compatible JavaScript', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'solvit-sdk-'));
  try {
    const output = join(dir, 'echo.solvit.json');
    await buildPlugin(resolve('../plugin-examples/echo/index.ts'), resolve('../plugin-examples/echo/manifest.json'), output);
    const pkg = JSON.parse(await readFile(output, 'utf8'));
    const sandbox = vm.createContext({});
    vm.runInContext(pkg.source, sandbox, { timeout: 1000 });
    assert.equal(pkg.manifest.id, 'example.echo');
    const response = await sandbox.solvitPlugin.tools.echo({ text: '你好' }, {});
    assert.deepEqual(JSON.parse(JSON.stringify(response)), { text: '你好' });
    await assert.rejects(() => sandbox.solvitPlugin.tools.echo({ text: 123 }, {}), /Invalid text/);
  } finally { await rm(dir, { recursive: true }); }
});

test('rejects Node dependencies unavailable inside Android QuickJS', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'solvit-sdk-'));
  try {
    const entry = join(dir, 'bad.ts');
    await writeFile(entry, "import fs from 'node:fs'; export default fs;");
    await assert.rejects(() => buildPlugin(entry, resolve('../plugin-examples/echo/manifest.json'), join(dir, 'bad.json')));
  } finally { await rm(dir, { recursive: true }); }
});
