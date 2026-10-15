import type { PluginContext } from '../../plugin-sdk/src/index';
export interface HttpConnection { name:string; url:string; token:string; headers:Record<string,string>; disabled:boolean; type?:'http' }
export interface StdioConnection { name:string; type:'stdio'; command:string; args:string[]; env:Record<string,string>; cwd:string; disabled:boolean }
export type Connection=HttpConnection|StdioConnection;
export const isLocal=(profile:Connection):profile is StdioConnection=>profile.type==='stdio';
const object = (value:unknown):value is Record<string,unknown> => !!value&&typeof value==='object'&&!Array.isArray(value);
const reserved = new Set(['host','content-length','content-type','accept','origin','connection','mcp-protocol-version','mcp-session-id']);
export function validateConnections(value:unknown):Connection[] {
  if(!Array.isArray(value)||value.length>8)throw new Error('Provide at most eight MCP servers');
  const names=new Set<string>();
  const result:Connection[]=value.map(raw=>{
    if(!object(raw)||typeof raw.name!=='string'||!raw.name.trim()||raw.name!==raw.name.trim()||raw.name.length>40||/[\x00-\x1f\x7f]/.test(raw.name)||['__proto__','prototype','constructor'].includes(raw.name)||names.has(raw.name))throw new Error('Invalid or duplicate MCP server name');
    names.add(raw.name);
    const disabled=raw.disabled??false;
    if(typeof disabled!=='boolean')throw new Error('Invalid MCP enabled state');
    if(raw.type==='stdio'){
      if(Object.keys(raw).some(k=>!['name','type','command','args','env','cwd','disabled'].includes(k)))throw new Error('Unexpected local MCP configuration');
      const args=raw.args??[],env=raw.env??{},cwd=raw.cwd??'';
      if(typeof raw.command!=='string'||!raw.command.trim()||raw.command.length>1024||raw.command.startsWith('-')||/[=\x00]/.test(raw.command)||
        !Array.isArray(args)||args.length>64||args.some(v=>typeof v!=='string'||v.length>16000||v.includes('\0'))||!object(env)||Object.keys(env).length>32||
        Object.entries(env).some(([k,v])=>!(/^[A-Za-z_][A-Za-z0-9_]{0,127}$/).test(k)||typeof v!=='string'||v.length>16000||v.includes('\0'))||
        typeof cwd!=='string'||cwd.length>1024||/[\\\x00]/.test(cwd)||(cwd!==''&&cwd.split('/').some(v=>!v||v==='.'||v==='..')))throw new Error('Invalid local MCP command, arguments, environment or relative directory');
      return {name:raw.name,type:'stdio',command:raw.command,args:args as string[],env:env as Record<string,string>,cwd,disabled};
    }
    if((raw.type!==undefined&&raw.type!=='http')||Object.keys(raw).some(k=>!['name','type','url','token','headers','disabled'].includes(k)))throw new Error('Unsupported MCP transport');
    if(typeof raw.url!=='string'||raw.url.length>1000||!/^https:\/\/(?:[a-zA-Z0-9.-]+|\[[a-fA-F0-9:]+\])(?::[0-9]{1,5})?(?:[/?][^\s#]*)?$/.test(raw.url))throw new Error('MCP requires a HTTPS endpoint without embedded credentials or fragment');
    const token=raw.token??'',headers=raw.headers??{};
    if(typeof token!=='string'||token.length>1024||/[\r\n]/.test(token)||!object(headers)||Object.keys(headers).length>8)throw new Error('Invalid MCP authentication');
    const clean:Record<string,string>={};const seen=new Set<string>();
    for(const [key,val]of Object.entries(headers)){
      if(!/^[a-zA-Z][a-zA-Z0-9-]{0,63}$/.test(key)||reserved.has(key.toLowerCase())||seen.has(key.toLowerCase())||typeof val!=='string'||val.length>1024||/[\r\n]/.test(val))throw new Error('Invalid MCP header');
      seen.add(key.toLowerCase());clean[key]=val;
    }
    if(token&&seen.has('authorization'))throw new Error('Choose bearer token or Authorization header');
    // Preserve existing serialized HTTPS profiles and credentials without migration.
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
    if(!object(raw))throw new Error('Invalid MCP server');
    if(raw.type==='stdio')return {...raw,name};
    if(raw.command!==undefined||!['streamable_http','streamable-http','http'].includes(String(raw.type))||
      Object.keys(raw).some(k=>!['type','url','headers','disabled'].includes(k)))throw new Error('Declare either HTTPS Streamable HTTP or local stdio explicitly');
    return {name,url:raw.url,headers:raw.headers,disabled:raw.disabled};
  }));
}
