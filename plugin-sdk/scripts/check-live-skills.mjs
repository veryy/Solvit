import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const endpoint = process.env.SOLVIT_MARKET_ENDPOINT ?? 'https://solvit.207.57.126.73.sslip.io';
const path = process.env.SOLVIT_SKILLS_PACKAGE ?? fileURLToPath(new URL('../../market-server/packages/dev.solvit.skills.solvit.json', import.meta.url));
const expected = await readFile(path);
const expectedManifest = JSON.parse(expected.toString('utf8')).manifest;
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
async function catalog(api) {
  const response = await fetch(`${endpoint}/api/v1/plugins?hostApi=${api}`, { signal: AbortSignal.timeout(10_000) });
  if (!response.ok) throw new Error('Live marketplace unavailable');
  return (await response.json()).plugins;
}
const old = await catalog(2);
if (old.some(plugin => plugin.apiVersion > 2)) throw new Error('Legacy filtering failed');
// API3 hosts keep the historical package; current hosts must receive the current package.
const legacyBytes = await readFile(new URL('../../market-server/packages/legacy/dev.solvit.skills-1.0.2.solvit.json', import.meta.url));
const legacy = (await catalog(3)).find(plugin => plugin.id === 'dev.solvit.skills');
if (!legacy || legacy.apiVersion !== 3 || legacy.version !== '1.0.2' || legacy.sha256 !== digest(legacyBytes)) {
  throw new Error('Live legacy Skills compatibility failed');
}
const legacyResponse = await fetch(new URL(legacy.downloadPath, endpoint), { signal: AbortSignal.timeout(10_000) });
if (!legacyResponse.ok || digest(Buffer.from(await legacyResponse.arrayBuffer())) !== digest(legacyBytes)) {
  throw new Error('Live legacy Skills download integrity failed');
}
const latest = await catalog(expectedManifest.apiVersion);
const entry = latest.find(plugin => plugin.id === 'dev.solvit.skills');
if (!entry || entry.apiVersion !== expectedManifest.apiVersion || entry.version !== expectedManifest.version ||
    entry.sha256 !== digest(expected)) throw new Error('Live Skills package differs from committed package');
const response = await fetch(new URL(entry.downloadPath, endpoint), { signal: AbortSignal.timeout(10_000) });
if (!response.ok) throw new Error('Live Skills download failed');
const bytes = Buffer.from(await response.arrayBuffer());
if (bytes.length !== expected.length || digest(bytes) !== entry.sha256) throw new Error('Live Skills integrity failed');
const pkg = JSON.parse(bytes.toString('utf8'));
const sandbox = vm.createContext({});
vm.runInContext(pkg.source, sandbox, { timeout: 1000 });
const data = new Map();
const clone = value => JSON.parse(JSON.stringify(value));
const ctx = { role: { id: 'writing-role', name: 'Writing', skillIds: ['writing'] }, storage: {
  async get(key) { return data.has(key) ? clone(data.get(key)) : null; },
  async set(key, value) { data.set(key, clone(value)); return null; },
} };
await sandbox.solvitPlugin.activate(ctx);
await sandbox.solvitPlugin.tools.skills_import({ files: { 'SKILL.md': '---\nname: writing\ndescription: Help write prose\n---\nLIVE_BODY_SENTINEL: use concrete words.' } }, ctx);
const context = await sandbox.solvitPlugin.beforeModel({ role: ctx.role, userText: 'write' }, ctx);
if (!context.includes('writing') || context.includes('code-review') || context.includes('LIVE_BODY_SENTINEL')) throw new Error('Live Skills progressive disclosure failed');
const loaded = await sandbox.solvitPlugin.tools.skills_load({ name: 'writing' }, ctx);
if (!loaded.instructions.includes('LIVE_BODY_SENTINEL')) throw new Error('Live Skills loading failed');
console.log('Live HTTPS Skills catalog, download integrity, role filtering and on-demand loading passed');
