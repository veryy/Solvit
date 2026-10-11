import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
const pkg = JSON.parse(await readFile(new URL('../../market-server/packages/dev.solvit.web-search.solvit.json', import.meta.url), 'utf8'));
const sandbox = vm.createContext({});
vm.runInContext(pkg.source, sandbox, { timeout: 1000 });
const visited = [];
const ctx = { settings: { get: async () => null }, http: { async request(options) {
  const url = new URL(options.url);
  if (!['cn.bing.com','www.bing.com'].includes(url.hostname) || url.protocol !== 'https:') throw new Error('Unexpected search origin');
  if (options.headers.Authorization) throw new Error('Default search forwarded credentials');
  visited.push(url.hostname);
  const response = await fetch(url, { headers: options.headers, redirect: 'follow', signal: AbortSignal.timeout(15000) });
  return { status: response.status, headers: Object.fromEntries(response.headers), body: await response.text() };
} } };
const result = await sandbox.solvitPlugin.tools.search({ query: 'Kotlin programming language', limit: 3 }, ctx);
if (result.error || !result.results?.length) throw new Error('Live public search unavailable: ' + (result.error ?? 'empty results'));
for (const source of result.results) {
  if (!source.title || !source.url.startsWith('https://')) throw new Error('Live search returned invalid source');
}
console.log('No-configuration public search passed: ' + result.results.length + ' sources; origins ' + visited.join(', '));
