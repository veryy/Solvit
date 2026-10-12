import { build } from 'esbuild';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export async function buildPlugin(entry, manifestFile, output) {
  const manifest = JSON.parse(await readFile(manifestFile, 'utf8'));
  if (![1, 2, 3, 4, 5, 6, 7, 8, 9].includes(manifest.apiVersion) || !Array.isArray(manifest.tools)) throw new Error('Invalid manifest');
  const pages = manifest.pages ?? [];
  if (!Array.isArray(pages) || pages.length > 8 || pages.length && manifest.apiVersion < 9 ||
      pages.some(page => !page || Object.keys(page).sort().join(',') !== 'id,title' ||
        typeof page.id !== 'string' || !/^[a-z][a-zA-Z0-9_-]{0,39}$/.test(page.id) ||
        typeof page.title !== 'string' || !page.title.trim() || page.title.length > 128) ||
      new Set(pages.map(page => page.id)).size !== pages.length) throw new Error('Invalid plugin pages');
  const hooks = manifest.hooks ?? [];
  if (!Array.isArray(hooks) || hooks.some(hook => hook !== 'beforeModel' && hook !== 'afterTurn') ||
      hooks.includes('beforeModel') && manifest.apiVersion < 3 || hooks.includes('afterTurn') && manifest.apiVersion < 8) throw new Error('Invalid hooks');
  if (hooks.includes('afterTurn')) {
    const field = (manifest.settings ?? []).find(field => field.key === manifest.afterTurnEnabledSetting);
    if (!field || field.kind !== 'boolean' || field.defaultValue !== undefined && field.defaultValue !== 'false' && field.defaultValue !== false) throw new Error('afterTurn requires a default-off setting');
  } else if (manifest.afterTurnEnabledSetting !== undefined || (manifest.permissions ?? []).includes('model')) throw new Error('Model permission requires afterTurn');
  const result = await build({
    entryPoints: [entry], bundle: true, write: false, format: 'iife', globalName: '__solvitBundle',
    platform: 'neutral', target: 'es2020', legalComments: 'inline', minify: false,
    mainFields: ['module', 'main'],
    footer: { js: 'globalThis.solvitPlugin = __solvitBundle.default;' },
  });
  const payload = JSON.stringify({ manifest, source: result.outputFiles[0].text }, null, 2) + '\n';
  if (Buffer.byteLength(payload) > 2 * 1024 * 1024) throw new Error('Package exceeds 2 MiB');
  await mkdir(dirname(output), { recursive: true });
  await writeFile(output, payload);
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const [entry, manifest, output] = process.argv.slice(2);
  if (!entry || !manifest || !output) throw new Error('Usage: node scripts/build-plugin.mjs entry.ts manifest.json output.solvit.json');
  await buildPlugin(resolve(entry), resolve(manifest), resolve(output));
}
