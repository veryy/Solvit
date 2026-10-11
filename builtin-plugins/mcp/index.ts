import { definePlugin, textArgument, type JsonObject, type JsonValue, type PluginContext } from '../../plugin-sdk/src/index';

type RemoteTool = { name: string; description?: string; inputSchema: JsonObject };
type Session = { url: string; token: string; id?: string; protocol: string; server: JsonValue; tools: RemoteTool[] };
const VERSIONS = ['2025-11-25', '2025-06-18', '2025-03-26'];
let session: Session | null = null;
let sequence = 0;
function isObject(value: unknown): value is JsonObject { return !!value && typeof value === 'object' && !Array.isArray(value); }
function headers(state: Session): Record<string, string> {
  const result: Record<string, string> = { Accept: 'application/json, text/event-stream', 'MCP-Protocol-Version': state.protocol };
  if (state.id) result['Mcp-Session-Id'] = state.id;
  if (state.token) result.Authorization = `Bearer ${state.token}`;
  return result;
}
function messages(body: string, contentType: string): JsonObject[] {
  if (!contentType.includes('text/event-stream')) {
    const value: unknown = JSON.parse(body);
    if (!isObject(value)) throw new Error('MCP response must be one JSON-RPC object');
    return [value];
  }
  const result: JsonObject[] = [];
  for (const frame of body.replace(/\r\n/g, '\n').split('\n\n')) {
    const data = frame.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).replace(/^ /, '')).join('\n');
    if (!data.trim()) continue;
    const value: unknown = JSON.parse(data);
    if (!isObject(value)) throw new Error('Invalid MCP SSE object');
    result.push(value);
  }
  return result;
}
class ExpiredSession extends Error {}
async function post(state: Session, ctx: PluginContext, payload: JsonObject) {
  const cancelBody = payload.method === 'tools/call' ? JSON.stringify({
    jsonrpc: '2.0', method: 'notifications/cancelled', params: { requestId: payload.id, reason: 'Client stopped the tool call' },
  }) : undefined;
  const result = await ctx.http.request({ url: state.url, method: 'POST', headers: headers(state), body: JSON.stringify(payload), cancelBody });
  if (result.status === 404 && state.id) { session = null; throw new ExpiredSession('MCP session expired; reconnect before retrying'); }
  if (result.status < 200 || result.status >= 300) throw new Error(`MCP HTTP ${result.status}`);
  return result;
}
async function notify(state: Session, ctx: PluginContext, method: string, params?: JsonObject) {
  await post(state, ctx, { jsonrpc: '2.0', method, ...(params ? { params } : {}) });
}
async function rpc(state: Session, ctx: PluginContext, method: string, params: JsonObject): Promise<JsonObject> {
  const id = ++sequence;
  const response = await post(state, ctx, { jsonrpc: '2.0', id, method, params });
  const incoming = messages(response.body, response.headers['content-type'] ?? 'application/json');
  if (method === 'initialize') {
    const assigned = response.headers['mcp-session-id'];
    if (assigned) {
      if (!/^[\x21-\x7e]{1,1024}$/.test(assigned)) throw new Error('Invalid MCP session identifier');
      state.id = assigned;
    }
  }
  let matched: JsonObject | undefined;
  for (const message of incoming) {
    if (message.jsonrpc !== '2.0') throw new Error('Invalid MCP JSON-RPC version');
    if (typeof message.method === 'string' && message.id !== undefined) {
      // No sampling, roots or elicitation capability is advertised.
      const answer: JsonObject = message.method === 'ping' ? { result: {} } : { error: { code: -32601, message: 'Client capability unavailable' } };
      await post(state, ctx, { jsonrpc: '2.0', id: message.id, ...answer });
    } else if (message.id === id) {
      if (matched) throw new Error('Duplicate MCP response ID');
      matched = message;
    }
  }
  if (!matched) throw new Error('MCP response missing request ID');
  if (matched.error !== undefined) {
    // Server error messages may echo credentials; expose only the numeric code.
    const code = isObject(matched.error) && typeof matched.error.code === 'number' ? matched.error.code : 'unknown';
    throw new Error(`MCP JSON-RPC error ${code}`);
  }
  if (!isObject(matched.result)) throw new Error('MCP result must be an object');
  return matched.result;
}
async function connect(ctx: PluginContext): Promise<Session> {
  if (session) return session;
  const url = await ctx.settings.get('url');
  const token = await ctx.settings.get('token') ?? '';
  if (!url || !/^https:\/\//i.test(url) || /[\r\n]/.test(token)) throw new Error('Configure MCP HTTPS endpoint first');
  const state: Session = { url, token, protocol: VERSIONS[0], server: {}, tools: [] };
  try {
    const initialized = await rpc(state, ctx, 'initialize', {
      protocolVersion: VERSIONS[0], capabilities: {}, clientInfo: { name: 'Solvit MCP plugin', version: '1.0.0' },
    });
    if (typeof initialized.protocolVersion !== 'string' || !VERSIONS.includes(initialized.protocolVersion)) throw new Error('MCP protocol version unsupported');
    if (!isObject(initialized.capabilities) || !isObject(initialized.capabilities.tools)) throw new Error('MCP server does not provide tools');
    state.protocol = initialized.protocolVersion;
    state.server = initialized.serverInfo ?? {};
    await notify(state, ctx, 'notifications/initialized');
    const cursors = new Set<string>();
    let cursor: string | undefined;
    for (let page = 0; page < 8; page++) {
      const result = await rpc(state, ctx, 'tools/list', cursor ? { cursor } : {});
      if (!Array.isArray(result.tools)) throw new Error('Invalid MCP tools list');
      for (const candidate of result.tools) {
        if (!isObject(candidate) || typeof candidate.name !== 'string' || !candidate.name || candidate.name.length > 128 ||
            !isObject(candidate.inputSchema) || candidate.inputSchema.type !== 'object' ||
            (candidate.description !== undefined && typeof candidate.description !== 'string')) throw new Error('Invalid MCP tool descriptor');
        if (state.tools.some(tool => tool.name === candidate.name)) throw new Error('Duplicate MCP tool name');
        state.tools.push(candidate as unknown as RemoteTool);
      }
      if (state.tools.length > 200) throw new Error('MCP tool catalog exceeds 200 tools');
      if (result.nextCursor === undefined) { session = state; return state; }
      if (typeof result.nextCursor !== 'string' || !result.nextCursor || cursors.has(result.nextCursor)) throw new Error('Invalid MCP pagination cursor');
      cursor = result.nextCursor; cursors.add(cursor);
    }
    throw new Error('MCP pagination exceeds eight pages');
  } catch (error) { await close(state, ctx); throw error; }
}
async function close(state: Session, ctx: PluginContext) {
  if (state.id) {
    try { await ctx.http.request({ url: state.url, method: 'DELETE', headers: headers(state) }); } catch { /* Best-effort server cleanup. */ }
  }
}
export default definePlugin({
  async deactivate(ctx) {
    const previous = session; session = null;
    if (previous) await close(previous, ctx);
  },
  tools: {
    async mcp_connect(_args, ctx) {
      const state = await connect(ctx);
      return { protocol: state.protocol, server: state.server, tools: state.tools.length };
    },
    async mcp_tools(args, ctx) {
      const state = await connect(ctx);
      const offset = args.offset ?? 0, limit = args.limit ?? 20;
      if (typeof offset !== 'number' || !Number.isInteger(offset) || offset < 0 || typeof limit !== 'number' || !Number.isInteger(limit) || limit < 1 || limit > 30) throw new Error('Invalid tool page');
      return { tools: state.tools.slice(offset, offset + limit) as unknown as JsonValue, total: state.tools.length };
    },
    async mcp_call(args, ctx) {
      const name = textArgument(args, 'name', 128);
      if (!isObject(args.arguments)) throw new Error('MCP arguments must be an object');
      const state = await connect(ctx);
      if (!state.tools.some(tool => tool.name === name)) throw new Error('MCP tool was not discovered');
      // No automatic retry of calls: a network error can follow an external side effect.
      return await rpc(state, ctx, 'tools/call', { name, arguments: args.arguments });
    },
    async mcp_disconnect(_args, ctx) {
      const previous = session; session = null;
      if (previous) await close(previous, ctx);
      return { disconnected: true };
    },
  },
});
