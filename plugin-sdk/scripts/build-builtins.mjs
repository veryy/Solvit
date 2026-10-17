import { readdir, readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { buildPlugin } from './build-plugin.mjs';
const root = resolve('../builtin-plugins');
const plugins = await readdir(root, { withFileTypes: true }).catch(error => {
  if (error.code === 'ENOENT') return [];
  throw error;
});
for (const directory of plugins.filter(entry => entry.isDirectory())) {
  const path = join(root, directory.name);
  const manifest = JSON.parse(await readFile(join(path, 'manifest.json'), 'utf8'));
  const output = resolve(`../market-server/packages/${manifest.id}.solvit.json`);
  await buildPlugin(join(path, 'index.ts'), join(path, 'manifest.json'), output);
  const asset = resolve(`../plugin-market/src/main/assets/builtin-plugins/${manifest.id}.solvit.json`);
  await mkdir(resolve('../plugin-market/src/main/assets/builtin-plugins'), { recursive: true });
  await writeFile(asset, await readFile(output));
}
// Optional installable UI example; do not seed it as an Android built-in.
const notes = resolve('../plugin-examples/notes');
await buildPlugin(join(notes, 'index.ts'), join(notes, 'manifest.json'),
  resolve('../market-server/packages/example.notes.solvit.json'));

const agent = resolve('../plugin-examples/agent');
await buildPlugin(join(agent, 'index.ts'), join(agent, 'manifest.json'),
  resolve('../market-server/packages/example.agent.solvit.json'));

const policy = resolve('../plugin-examples/agent-policy');
await buildPlugin(join(policy, 'index.ts'), join(policy, 'manifest.json'),
  resolve('../market-server/packages/example.agent-policy.solvit.json'));

// UI-only upgrades for users who have not granted workspace or local-execution access.
// Keep the immutable historical source and manifest contracts; share current page resources.
for (const id of ['skills', 'mcp']) {
  const historical = JSON.parse(await readFile(resolve(`../market-server/packages/legacy/dev.solvit.${id}-1.1.1.solvit.json`), 'utf8'));
  const current = JSON.parse(await readFile(resolve(`../market-server/packages/dev.solvit.${id}.solvit.json`), 'utf8'));
  const payload = JSON.stringify({ ...historical,
    manifest: { ...historical.manifest, version: '1.1.2' }, resources: current.resources }, null, 2) + '\n';
  await writeFile(resolve(`../market-server/packages/legacy/dev.solvit.${id}-1.1.2.solvit.json`), payload);
  await writeFile(resolve(`../plugin-market/src/main/assets/builtin-compat/${id}-1.1.2.solvit.json`), payload);
}
