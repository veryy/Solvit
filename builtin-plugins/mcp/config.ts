import type { PluginContext } from '../../plugin-sdk/src/index';
export interface Connection { name:string; url:string; token:string; headers:Record<string,string>; disabled:boolean }
const object = (value:unknown):value is Record<string,unknown> => !!value&&typeof value==='object'&&!Array.isArray(value);
const reserved = new Set(['host','content-length','content-type','accept','origin','connection','mcp-protocol-version','mcp-session-id']);
export function validateConnections(value:unknown):Connection[] {
  if(!Array.isArray(value)||value.length>8)throw new Error('Provide at most eight MCP servers');
  const names=new Set<string>();
  const result=value.map(raw=>{
    if(!object(raw)||Object.keys(raw).some(k=>!['name','url','token','headers','disabled'].includes(k))||
      typeof raw.name!=='string'||!raw.name.trim()||raw.name!==raw.name.trim()||raw.name.length>40||/[\x00-\x1f\x7f]/.test(raw.name)||['__proto__','prototype','constructor'].includes(raw.name)||names.has(raw.name))throw new Error('Invalid or duplicate MCP server name');
    names.add(raw.name);
    if(typeof raw.url!=='string'||raw.url.length>1000||!/^https:\/\/(?:[a-zA-Z0-9.-]+|\[[a-fA-F0-9:]+\])(?::[0-9]{1,5})?(?:[/?][^\s#]*)?$/.test(raw.url))throw new Error('MCP requires a HTTPS endpoint without embedded credentials or fragment');
    const token=raw.token??'',headers=raw.headers??{},disabled=raw.disabled??false;
    if(typeof token!=='string'||token.length>1024||/[\r\n]/.test(token)||typeof disabled!=='boolean'||!object(headers)||Object.keys(headers).length>8)throw new Error('Invalid MCP authentication or enabled state');
    const clean:Record<string,string>={};const seen=new Set<string>();
    for(const [key,val]of Object.entries(headers)){
      if(!/^[a-zA-Z][a-zA-Z0-9-]{0,63}$/.test(key)||reserved.has(key.toLowerCase())||seen.has(key.toLowerCase())||typeof val!=='string'||val.length>1024||/[\r\n]/.test(val))throw new Error('Invalid MCP header');
      seen.add(key.toLowerCase());clean[key]=val;
    }
    if(token&&seen.has('authorization'))throw new Error('Choose bearer token or Authorization header');
    return {name:raw.name,url:raw.url,token,headers:clean,disabled};
  });
  if(JSON.stringify(result).length>4096)throw new Error('MCP configuration exceeds 4096 characters');
  return result;
}
export async function readConnections(ctx:PluginContext):Promise<{encoded:string|null;servers:Connection[]}> {
  const encoded=await ctx.settings.get('connections');
  if(encoded!==null&&encoded!=='')return {encoded,servers:validateConnections(JSON.parse(encoded))};
  const url=await ctx.settings.get('url');const token=await ctx.settings.get('token')??'';
  return {encoded,servers:url?[...validateConnections([{name:'default',url,token}])]:[]};
}
export function importConnections(text:string):Connection[] {
  if(text.length>16_384)throw new Error('MCP import exceeds limits');
  const root:unknown=JSON.parse(text);
  if(!object(root)||Object.keys(root).some(k=>k!=='mcpServers')||!object(root.mcpServers))throw new Error('Provide a mcpServers object');
  return validateConnections(Object.entries(root.mcpServers).map(([name,raw])=>{
    if(!object(raw)||raw.command!==undefined||!['streamable_http','streamable-http','http'].includes(String(raw.type))||
      Object.keys(raw).some(k=>!['type','url','headers','disabled'].includes(k)))throw new Error('Only explicitly declared HTTPS Streamable HTTP is supported');
    return {name,url:raw.url,headers:raw.headers,disabled:raw.disabled};
  }));
}
