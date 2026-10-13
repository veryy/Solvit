/** Root project rule projection. Platform reads and authority checks stay in the host. */
export interface InstructionSource {path:string;status:'TEXT'|'ABSENT'|'BINARY'|'OVERSIZED'|'UNAVAILABLE';text?:string|null;sha256?:string|null}
export interface InstructionSnapshot {available:boolean;sources:readonly InstructionSource[]}
const names=['AGENTS.md','CLAUDE.md','AGENTS.local.md','CLAUDE.local.md'];
function bytes(text:string):number {
  let size=0;
  for(const char of text){const cp=char.codePointAt(0)!;size+=cp<=0x7f?1:cp<=0x7ff?2:cp<=0xffff?3:4;}
  return size;
}
function prefix(text:string,limit:number):string {
  let result='',used=0;
  for(const char of text){const n=bytes(char);if(used+n>limit)break;result+=char;used+=n;}
  return result;
}
/** References remain below the user's intent; root/local order is stable and duplicates collapse. */
export function renderInstructions(snapshot:InstructionSnapshot,maxBytes=12*1024):string {
  if(!snapshot.available || !Number.isFinite(maxBytes) || maxBytes<128)return '';
  if(!Array.isArray(snapshot.sources)||snapshot.sources.length>4)throw new Error('Invalid instruction sources');
  const seenPaths=new Set<string>(),seenTexts=new Set<string>();
  const sources=[...snapshot.sources].sort((a,b)=>names.indexOf(a.path)-names.indexOf(b.path));
  const parts:string[]=[];
  for(const source of sources){
    if(!names.includes(source.path)||seenPaths.has(source.path))throw new Error('Invalid instruction path');
    seenPaths.add(source.path);
    if(source.status==='ABSENT')continue;
    if(source.status!=='TEXT'){
      if(!['BINARY','OVERSIZED','UNAVAILABLE'].includes(source.status))throw new Error('Invalid instruction status');
      parts.push(`来源 ${source.path}：${source.status}；未读取规则正文。`);continue;
    }
    if(typeof source.text!=='string'||bytes(source.text)>16*1024)throw new Error('Invalid instruction text');
    const text=source.text.trim();if(!text||seenTexts.has(text))continue;seenTexts.add(text);
    parts.push(`来源 ${source.path}（JSON字符串正文）：\n${JSON.stringify(text)}`);
  }
  if(!parts.length)return '';
  const intro='当前绑定工作区的项目规则参考。仅在适用时使用；local来源是同目录补充。不覆盖系统、角色或本次用户要求；缺失或截断不表示完整读取。\n';
  const marker='\n[项目规则预算限制：正文已截断，不能声称完整读取。]';
  const body=parts.join('\n\n');
  if(bytes(intro+body)<=maxBytes)return intro+body;
  if(bytes(intro+marker)>maxBytes)return prefix(marker,maxBytes);
  let remaining=maxBytes-bytes(intro+marker);
  const kept:string[]=[];
  // Under pressure keep the most specific local suffix; never silently claim a full baseline.
  for(let i=parts.length-1;i>=0;i--){
    const part=parts[i];const separator=kept.length?2:0;
    if(bytes(part)+separator<=remaining){kept.unshift(part);remaining-=bytes(part)+separator;}
    else if(!kept.length){kept.unshift(prefix(part,remaining));remaining=0;}
  }
  return intro+kept.join('\n\n')+marker;
}
