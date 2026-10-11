import vm from 'node:vm';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const endpoint = process.env.SOLVIT_MCP_ENDPOINT;
if (!endpoint?.startsWith('https://')) throw new Error('Set SOLVIT_MCP_ENDPOINT to the HTTPS demo endpoint');
const pkg = JSON.parse(await readFile(process.env.SOLVIT_MCP_PACKAGE ?? '../market-server/packages/dev.solvit.mcp.solvit.json', 'utf8'));
const sandbox = vm.createContext({}); vm.runInContext(pkg.source, sandbox, { timeout: 1000 });
const ctx = {
  settings: { async get(key) { return key === 'url' ? endpoint : ''; } },
  http: { async request(options) {
    const response = await fetch(options.url, {method:options.method,headers:options.headers,body:options.body,signal:AbortSignal.timeout(10_000)});
    return { status:response.status,body:await response.text(),headers:Object.fromEntries(response.headers) };
  } },
};
const connected = await sandbox.solvitPlugin.tools.mcp_connect({},ctx);
assert.equal(connected.tools,2);
const tools = await sandbox.solvitPlugin.tools.mcp_tools({},ctx);
assert.ok(tools.tools.some(tool=>tool.name==='echo'));
const result = await sandbox.solvitPlugin.tools.mcp_call({name:'echo',arguments:{text:'Solvit 实际 HTTPS MCP 验证'}},ctx);
assert.equal(result.content[0].text,'Solvit 实际 HTTPS MCP 验证');
await sandbox.solvitPlugin.tools.mcp_disconnect({},ctx);
process.stdout.write('Live HTTPS MCP initialize, discovery, tool call and disconnect passed.\n');
