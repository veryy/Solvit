import { build } from 'esbuild';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export async function buildPlugin(entry, manifestFile, output) {
  const manifest = JSON.parse(await readFile(manifestFile, 'utf8'));
  if (![1, 2, 3, 4].includes(manifest.apiVersion) || !Array.isArray(manifest.tools)) throw new Error('Invalid manifest');
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
