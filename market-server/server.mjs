import http from 'node:http';
import { handleMcpDemo } from './mcp-demo.mjs';
import { readFile, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(fileURLToPath(import.meta.url));
const MAX_PACKAGE_BYTES = 2 * 1024 * 1024;
const pluginId = /^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+$/;
const version = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/;

export async function readCatalog(directory) {
  const files = (await readdir(directory)).filter(name => name.endsWith('.solvit.json')).sort();
  const legacy = await readdir(join(directory, 'legacy')).catch(error => {
    if (error.code === 'ENOENT') return [];
    throw error;
  });
  files.push(...legacy.filter(name => name.endsWith('.solvit.json')).map(name => join('legacy', name)));
  // A package moved to legacy can retain its original catalog tie-break position.
  // This data belongs to the catalog, not to any plugin-specific server logic.
  const placement = await readFile(join(directory, 'legacy', 'catalog-order.json'), 'utf8')
    .then(JSON.parse).catch(error => { if (error.code === 'ENOENT') return []; throw error; });
  if (!Array.isArray(placement) || placement.length > 100) throw new Error('Invalid legacy catalog order');
  for (const item of placement) {
    if (!item || typeof item.file !== 'string' || typeof item.after !== 'string' ||
        !/^[a-z0-9.-]+\.solvit\.json$/.test(item.file) || !/^[a-z0-9.-]+\.solvit\.json$/.test(item.after))
      throw new Error('Invalid legacy catalog order');
    const file = join('legacy', item.file);
    const position = files.indexOf(file);
    if (position < 0 || files.indexOf(item.after) < 0) continue;
    files.splice(position, 1);
    files.splice(files.indexOf(item.after) + 1, 0, file);
  }
  const entries = [];
  const packages = new Map();
  for (const file of files) {
    const bytes = await readFile(join(directory, file));
    if (bytes.length > MAX_PACKAGE_BYTES) throw new Error(`Oversized package: ${file}`);
    const { manifest, source } = JSON.parse(bytes.toString('utf8'));
    if (!manifest || !pluginId.test(manifest.id) || !version.test(manifest.version) || ![1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31].includes(manifest.apiVersion) ||
        typeof manifest.name !== 'string' || !manifest.name.trim() || typeof manifest.description !== 'string' ||
        typeof source !== 'string' || !source.trim() || !Array.isArray(manifest.permissions) ||
        !Array.isArray(manifest.dependencies) || !Array.isArray(manifest.tools)) throw new Error(`Invalid package: ${file}`);
    const key = `${manifest.id}/${manifest.version}`;
    if (packages.has(key)) throw new Error(`Duplicate plugin version: ${key}`);
    const sha256 = createHash('sha256').update(bytes).digest('hex');
    entries.push({ ...manifest, bytes: bytes.length, sha256,
      downloadPath: `/api/v1/packages/${manifest.id}/${manifest.version}` });
    packages.set(key, { manifest, bytes, sha256 });
  }
  return { entries, packages };
}

export function createMarketServer({ directory = join(root, 'packages') } = {}) {
  return http.createServer(async (request, response) => {
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Cache-Control', 'no-cache');
    const send = (status, body, contentType = 'application/json; charset=utf-8') => {
      response.writeHead(status, { 'Content-Type': contentType });
      response.end(typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body));
    };
    if (new URL(request.url, 'http://localhost').pathname === '/mcp-demo') {
      await handleMcpDemo(request, response); return;
    }
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      response.setHeader('Allow', 'GET, HEAD'); send(405, { error: 'Method not allowed' }); return;
    }
    try {
      const url = new URL(request.url, 'http://localhost');
      const path = url.pathname;
      if (path === '/health') { send(200, { status: 'ok', service: 'solvit-plugin-market' }); return; }
      if (path === '/') { send(200, await readFile(join(root, 'public/index.html')), 'text/html; charset=utf-8'); return; }
      if (path === '/api/v1/plugins') {
        const { entries } = await readCatalog(directory);
        const requested = Number(url.searchParams.get('hostApi'));
        const supported = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31].includes(requested) ? requested : 1;
        const versions = entries.filter(entry => entry.apiVersion <= supported).sort((a, b) => {
          const aa = a.version.split('.').map(Number), bb = b.version.split('.').map(Number);
          for (let i = 0; i < 3; i++) if (aa[i] !== bb[i]) return bb[i] - aa[i];
          return 0;
        });
        const plugins = [...new Map(versions.map(entry => entry.id).map(id => [id, versions.find(entry => entry.id === id)])).values()];
        send(200, { apiVersion: 1, plugins }); return;
      }
      const match = /^\/api\/v1\/packages\/([a-z0-9.-]+)\/([0-9.]+)$/.exec(path);
      if (match && pluginId.test(match[1]) && version.test(match[2])) {
        const entry = (await readCatalog(directory)).packages.get(`${match[1]}/${match[2]}`);
        if (entry?.manifest.version === match[2]) {
          response.setHeader('Content-Disposition', `attachment; filename="${match[1]}-${match[2]}.solvit.json"`);
          response.setHeader('ETag', `"${entry.sha256}"`);
          send(200, entry.bytes); return;
        }
      }
      send(404, { error: 'Not found' });
    } catch (error) {
      console.error('Market request failed:', error.message);
      send(500, { error: 'Marketplace temporarily unavailable' });
    }
  });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.SOLVIT_MARKET_PORT || 18765);
  const server = createMarketServer();
  server.listen(port, '127.0.0.1', () => console.log(`Solvit marketplace listening on 127.0.0.1:${port}`));
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)));
}
