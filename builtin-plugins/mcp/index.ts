import { definePlugin, textArgument, type JsonObject, type JsonValue, type PluginContext } from '../../plugin-sdk/src/index';
import { readConnections, isLocal, type Connection } from './config';
import { createMcpPage } from './pages';

type RemoteTool = { name: string; description?: string; inputSchema: JsonObject };
type Session = { key:string; scope?:string; processId?:string; name:string; fingerprint:string; customHeaders:Record<string,string>; url: string; token: string; id?: string; protocol: string; server: JsonValue; tools: RemoteTool[] };
const VERSIONS = ['2025-11-25', '2025-06-18', '2025-03-26'];
const sessions = new Map<string, Session>();
const failures = new Map<string, string>();
let sequence = 0;
function fail(key:string,code:string){while(failures.size>=64&&!failures.has(key)){const oldest=failures.keys().next().value;if(oldest===undefined)break;failures.delete(oldest)}failures.set(key,code)}
function isObject(value: unknown): value is JsonObject { return !!value && typeof value === 'object' && !Array.isArray(value); }
function headers(state: Session): Record<string, string> {
  const result: Record<string, string> = { ...state.customHeaders, Accept: 'application/json, text/event-stream', 'MCP-Protocol-Version': state.protocol };
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
  if (result.status === 404 && state.id) { sessions.delete(state.key); fail(state.key,'EXPIRED'); throw new ExpiredSession('MCP session expired; reconnect before retrying'); }
  if (result.status < 200 || result.status >= 300) throw new Error(`MCP HTTP ${result.status}`);
  return result;
}
async function notify(state: Session, ctx: PluginContext, method: string, params?: JsonObject) {
  const frame:JsonObject={jsonrpc:'2.0',method,...(params?{params}:{})};
  if(state.processId)await local(ctx).send(state.name,state.processId,frame);
  else await post(state,ctx,frame);
}
async function rpc(state: Session, ctx: PluginContext, method: string, params: JsonObject): Promise<JsonObject> {
  const id = ++sequence;
  if(state.processId)return localRpc(state,ctx,{jsonrpc:'2.0',id,method,params},id);
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
function local(ctx:PluginContext) {
  if(!ctx.stdio)throw new Error('Local MCP requires the updated Solvit host');
  return ctx.stdio;
}
async function localRpc(state:Session,ctx:PluginContext,payload:JsonObject,id:number):Promise<JsonObject> {
  try {
    const transport=local(ctx),handle=state.processId!;
    await transport.send(state.name,handle,payload);
    for(let count=0;count<128;count++){
      const message=await transport.receive(state.name,handle);
      if(message.jsonrpc!=='2.0')throw new Error('Invalid MCP JSON-RPC version');
      if(typeof message.method==='string'){
        if(message.id!==undefined){
          const answer:JsonObject=message.method==='ping'?{result:{}}:{error:{code:-32601,message:'Client capability unavailable'}};
          await transport.send(state.name,handle,{jsonrpc:'2.0',id:message.id,...answer});
        }
        continue;
      }
      if(message.id!==id)throw new Error('Unexpected MCP response ID');
      if(message.error!==undefined){
        const code=isObject(message.error)&&typeof message.error.code==='number'?message.error.code:'unknown';
        throw new Error(`MCP JSON-RPC error ${code}`);
      }
      if(!isObject(message.result))throw new Error('MCP result must be an object');
      return message.result;
    }
    throw new Error('MCP response limit exceeded');
  }catch(error){
    sessions.delete(state.key);fail(state.key,'CONNECTION_FAILED');await close(state,ctx);
    throw error;
  }
}
async function sessionKey(profile:Connection,ctx:PluginContext):Promise<string|null>{
  if(!isLocal(profile))return profile.name;
  const scope=await local(ctx).scope();return scope?`stdio:${scope}:${profile.name}`:null;
}
async function synchronize(ctx:PluginContext):Promise<Connection[]> {
  const {servers}=await readConnections(ctx);
  for(const [key,state]of sessions)if(!servers.some(server=>server.name===state.name&&!server.disabled&&JSON.stringify(server)===state.fingerprint)){
    sessions.delete(key);failures.delete(key);await close(state,ctx);
  }
  return servers;
}
async function connect(ctx: PluginContext, name?:string): Promise<Session> {
  const servers=await synchronize(ctx),enabled=servers.filter(server=>!server.disabled);
  const profile=name ? servers.find(server=>server.name===name) : enabled.length===1 ? enabled[0] : undefined;
  if(!profile)throw new Error('Select a configured MCP server with mcp_servers');
  if(profile.disabled)throw new Error('MCP server is disabled');
  const key=await sessionKey(profile,ctx);
  if(!key)throw new Error('Bind a workspace to the current chat before starting local MCP');
  const previous=sessions.get(key);
  // Recheck the native generation on each invocation: it may have expired or been revoked.
  const processId=isLocal(profile)?await local(ctx).open(profile.name):undefined;
  if(previous&&previous.processId===processId)return previous;
  if(previous){sessions.delete(key);await close(previous,ctx)}
  const state:Session={key,name:profile.name,fingerprint:JSON.stringify(profile),processId,
    ...(isLocal(profile)?{scope:key}:{}),customHeaders:isLocal(profile)?{}:profile.headers,
    url:isLocal(profile)?'':profile.url,token:isLocal(profile)?'':profile.token,protocol:VERSIONS[0],server:{},tools:[]};
  try {
    const initialized = await rpc(state, ctx, 'initialize', {
      protocolVersion: VERSIONS[0], capabilities: {}, clientInfo: { name: 'Solvit MCP plugin', version: '1.2.0' },
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
      if (result.nextCursor === undefined) {
        while(sessions.size>=16){const oldest=sessions.entries().next().value;if(!oldest)break;sessions.delete(oldest[0]);failures.delete(oldest[0]);await close(oldest[1],ctx)}
        sessions.set(state.key,state);failures.delete(state.key);return state;
      }
      if (typeof result.nextCursor !== 'string' || !result.nextCursor || cursors.has(result.nextCursor)) throw new Error('Invalid MCP pagination cursor');
      cursor = result.nextCursor; cursors.add(cursor);
    }
    throw new Error('MCP pagination exceeds eight pages');
  } catch (error) { fail(state.key,'CONNECTION_FAILED');await close(state, ctx); throw error; }
}
async function close(state: Session, ctx: PluginContext) {
  if(state.processId){try{await local(ctx).close(state.name,state.processId)}catch{/* Host lifecycle also closes revoked or disposed owners. */}return}
  if (state.id) {
    try { await ctx.http.request({ url: state.url, method: 'DELETE', headers: headers(state) }); } catch { /* Best-effort server cleanup. */ }
  }
}
function serverArgument(args:JsonObject):string|undefined { return args.server===undefined ? undefined : textArgument(args,'server',40); }
async function disconnect(ctx:PluginContext,name?:string) {
  const scope=ctx.stdio?await ctx.stdio.scope().catch(()=>null):null;
  const selected=[...sessions.values()].filter(state=>(!name||state.name===name)&&(!state.processId||(scope!==null&&state.key===`stdio:${scope}:${state.name}`)));
  selected.forEach(state=>{sessions.delete(state.key);failures.delete(state.key)});
  await Promise.all(selected.map(state=>close(state,ctx)));
}
async function probe(ctx:PluginContext,name:string) {
  try { const state=await connect(ctx,name);return {protocol:state.protocol,tools:state.tools.length}; }
  finally { await disconnect(ctx,name); }
}
export default definePlugin({
  pages:{main:createMcpPage(probe)},
  async deactivate(ctx) { await disconnect(ctx); },
  tools: {
    async mcp_servers(_args,ctx) {
      const servers=await synchronize(ctx);
      return {servers:await Promise.all(servers.map(async profile=>{
        const key=await sessionKey(profile,ctx),state=key?sessions.get(key):undefined;
        return {name:profile.name,transport:isLocal(profile)?'stdio':'https',enabled:!profile.disabled,
          status:profile.disabled?'disabled':state?'connected':key?failures.get(key)??'not_connected':'workspace_required',tools:state?.tools.length??0};
      }))};
    },
    async mcp_connect(args, ctx) {
      const state = await connect(ctx,serverArgument(args));
      return { connection:state.name,protocol: state.protocol, server: state.server, tools: state.tools.length };
    },
    async mcp_tools(args, ctx) {
      const state = await connect(ctx,serverArgument(args));
      const offset = args.offset ?? 0, limit = args.limit ?? 20;
      if (typeof offset !== 'number' || !Number.isInteger(offset) || offset < 0 || typeof limit !== 'number' || !Number.isInteger(limit) || limit < 1 || limit > 30) throw new Error('Invalid tool page');
      return { server:state.name,tools: state.tools.slice(offset, offset + limit) as unknown as JsonValue, total: state.tools.length };
    },
    async mcp_call(args, ctx) {
      const name = textArgument(args, 'name', 128);
      if (!isObject(args.arguments)) throw new Error('MCP arguments must be an object');
      const state = await connect(ctx,serverArgument(args));
      if (!state.tools.some(tool => tool.name === name)) throw new Error('MCP tool was not discovered');
      // Never replay a call after a failed response: the server may have changed external state.
      return { ...await rpc(state, ctx, 'tools/call', { name, arguments: args.arguments }),connection:state.name };
    },
    async mcp_disconnect(args, ctx) {
      await disconnect(ctx,serverArgument(args));
      return { disconnected: true };
    },
  },
});
