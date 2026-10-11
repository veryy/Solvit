import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile, mkdir } from 'node:fs/promises';
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

test('includes pure JavaScript package dependencies without runtime module loading', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'solvit-sdk-'));
  try {
    const dependency = join(dir, 'node_modules/pure-format');
    await mkdir(dependency, { recursive: true });
    await writeFile(join(dependency, 'package.json'), JSON.stringify({ name: 'pure-format', type: 'module', main: 'index.js' }));
    await writeFile(join(dependency, 'index.js'), 'export const format = value => `formatted:${value}`;');
    const entry = join(dir, 'entry.ts'), output = join(dir, 'package.json');
    await writeFile(entry, "import {format} from 'pure-format'; export default {tools:{echo: args => ({text:format(args.text)})}};");
    await buildPlugin(entry, resolve('../plugin-examples/echo/manifest.json'), output);
    const sandbox = vm.createContext({});
    vm.runInContext(JSON.parse(await readFile(output, 'utf8')).source, sandbox, { timeout: 1000 });
    assert.equal(sandbox.solvitPlugin.tools.echo({ text: 'dependency' }).text, 'formatted:dependency');
  } finally { await rm(dir, { recursive: true }); }
});
