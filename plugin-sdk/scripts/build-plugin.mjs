import { build } from 'esbuild';
import { readFile, writeFile, mkdir, readdir, lstat } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';

const resourcePath = value => typeof value === 'string' && value.length <= 160 &&
  value.split('/').every(part => /^[a-zA-Z0-9_-][a-zA-Z0-9._-]*$/.test(part)) && /\.(html|js|css|svg|json|txt)$/.test(value);
async function webResources(manifest, manifestFile) {
  const roots = [...new Set((manifest.pages ?? []).filter(page => page.entry).map(page => dirname(page.entry)))];
  const resources = {};
  for (const root of roots) {
    const walk = async relative => {
      if ((await lstat(resolve(dirname(manifestFile),relative))).isSymbolicLink()) throw new Error('Plugin resources must not be symlinks');
      for (const file of (await readdir(resolve(dirname(manifestFile), relative), { withFileTypes:true })).sort((a,b)=>a.name.localeCompare(b.name))) {
        const path = `${relative}/${file.name}`;
        if (file.isSymbolicLink()) throw new Error('Plugin resources must not be symlinks');
        if (file.isDirectory()) await walk(path);
        else if (file.name.endsWith('.ts') || file.name.endsWith('.mjs')) {
          const target = path.replace(/\.(ts|mjs)$/, '.js');
          if (!resourcePath(target) || resources[target] !== undefined) throw new Error('Invalid or duplicate resource');
          const bundle = await build({entryPoints:[resolve(dirname(manifestFile),path)],bundle:true,write:false,
            platform:'browser',format:'iife',target:'es2020',minify:true,legalComments:'inline',
            nodePaths:[fileURLToPath(new URL('../node_modules',import.meta.url))]});
          resources[target] = bundle.outputFiles[0].text;
        } else {
          if (!resourcePath(path) || resources[path] !== undefined) throw new Error('Invalid or duplicate resource');
          resources[path] = await readFile(resolve(dirname(manifestFile),path),'utf8');
        }
      }
    };
    await walk(root);
  }
  if (Object.keys(resources).length > 32 || Object.values(resources).reduce((bytes,text)=>bytes+Buffer.byteLength(text),0)>1024*1024)
    throw new Error('Plugin resources exceed limits');
  for (const page of manifest.pages ?? []) if (page.entry && !resources[page.entry]) throw new Error('Missing HTML entry');
  return resources;
}

export async function buildPlugin(entry, manifestFile, output) {
  const manifest = JSON.parse(await readFile(manifestFile, 'utf8'));
  if (![1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18].includes(manifest.apiVersion) || !Array.isArray(manifest.tools)) throw new Error('Invalid manifest');
  if (manifest.nativeServices !== undefined && (manifest.apiVersion < 12 || !Array.isArray(manifest.nativeServices) ||
      manifest.nativeServices.some(service => !(service==='terminal.pty' && manifest.permissions?.includes('shell') || service==='web.pages' && manifest.permissions?.includes('network'))))) throw new Error('Invalid native services');
  if (manifest.permissions?.includes('agent.read') && (manifest.apiVersion < 15 || !manifest.permissions.includes('agent')))
    throw new Error('Agent observation requires API15 and agent permission');
  const pages = manifest.pages ?? [];
  if (!Array.isArray(pages) || pages.length > 8 || pages.length && manifest.apiVersion < 9 ||
      pages.some(page => !page || !['id,title', ...(manifest.apiVersion>=10?['entry,id,title']:[]), ...(manifest.apiVersion>=17?['context,entry,id,title']:[])].includes(Object.keys(page).sort().join(',')) ||
        page.context !== undefined && (!['management','execution'].includes(page.context) || page.context==='execution'&&!manifest.permissions?.includes('workspace')) ||
        page.entry !== undefined && (!resourcePath(page.entry) || !page.entry.endsWith('.html') || !page.entry.includes('/')) ||
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
  if (manifest.agentEnabledSetting !== undefined) {
    const field = (manifest.settings ?? []).find(field => field.key === manifest.agentEnabledSetting);
    if (manifest.apiVersion < 14 || !manifest.permissions?.includes('agent') || !field || field.kind !== 'boolean' || field.pageManaged ||
        field.defaultValue !== undefined && field.defaultValue !== 'false') throw new Error('Agent requires API14, permission and a default-off setting');
  } else if (manifest.permissions?.includes('agent')) throw new Error('Agent permission requires strategy declaration');
  const result = await build({
    entryPoints: [entry], bundle: true, write: false, format: 'iife', globalName: '__solvitBundle',
    platform: 'neutral', target: 'es2020', legalComments: 'inline', minify: false,
    mainFields: ['module', 'main'],
    footer: { js: 'globalThis.solvitPlugin = __solvitBundle.default;' },
  });
  const resources = await webResources(manifest, manifestFile);
  const payload = JSON.stringify({ manifest, source: result.outputFiles[0].text,
    ...(Object.keys(resources).length ? {resources}: {}) }, null, 2) + '\n';
  if (Buffer.byteLength(payload) > 2 * 1024 * 1024) throw new Error('Package exceeds 2 MiB');
  await mkdir(dirname(output), { recursive: true });
  await writeFile(output, payload);
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const [entry, manifest, output] = process.argv.slice(2);
  if (!entry || !manifest || !output) throw new Error('Usage: node scripts/build-plugin.mjs entry.ts manifest.json output.solvit.json');
  await buildPlugin(resolve(entry), resolve(manifest), resolve(output));
}
