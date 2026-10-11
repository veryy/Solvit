import { readdir } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { buildPlugin } from './build-plugin.mjs';
const root = resolve('../builtin-plugins');
const plugins = await readdir(root, { withFileTypes: true }).catch(error => {
  if (error.code === 'ENOENT') return [];
  throw error;
});
for (const directory of plugins.filter(entry => entry.isDirectory())) {
  const path = join(root, directory.name);
  const manifest = JSON.parse(await (await import('node:fs/promises')).readFile(join(path, 'manifest.json'), 'utf8'));
  await buildPlugin(join(path, 'index.ts'), join(path, 'manifest.json'), resolve(`../market-server/packages/${manifest.id}.solvit.json`));
}
