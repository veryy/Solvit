import {structuredPatch} from '../../plugin-sdk/src/line-diff';

export type DiffOperation='context'|'added'|'removed'|'header'|'marker';
export interface DiffRow {operation:DiffOperation;text:string;beforeLine:number|null;afterLine:number|null;continuation:boolean}
export interface FileDiff {coarse:boolean;rows:DiffRow[]}
const MAX_BYTES=256*1024;
export const DIFF_PAGE_ROWS=16;
function validate(text:string){
 let bytes=0;
 for(const char of text){const point=char.codePointAt(0)!;
  if(point===0||point>=0xd800&&point<=0xdfff)throw Error('Invalid captured UTF-8 text');
  bytes+=point>65535?4:point>2047?3:point>127?2:1;
 }
 if(bytes>MAX_BYTES)throw Error('Captured file exceeds 256 KiB');
}
/** Only caller-provided immutable copies; no filesystem or current-file fallback. */
export function fileDiff(before:string,after:string,options:{maxEditLength?:number;timeout?:number}={}):FileDiff {
 validate(before);validate(after);
 const rows:DiffRow[]=[];
 const add=(operation:DiffOperation,text:string,beforeLine:number|null,afterLine:number|null)=>{
  let start=0;
  do {let end=Math.min(text.length,start+1024);
   if(end<text.length&&end>start&&/[\uD800-\uDBFF]/.test(text[end-1])&&/[\uDC00-\uDFFF]/.test(text[end]))end--;
   rows.push({operation,text:text.slice(start,end),beforeLine,afterLine,continuation:start>0});start=end;
  }while(start<text.length);
 };
 const patch=structuredPatch('before','after',before,after,undefined,undefined,
  {context:3,timeout:options.timeout??75,...(options.maxEditLength===undefined?{}:{maxEditLength:options.maxEditLength})});
 if(patch){
  for(const hunk of patch.hunks){
   add('header',`@@ -${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@`,null,null);
   let old=hunk.oldStart,next=hunk.newStart;
   for(const line of hunk.lines){
    const prefix=line[0];
    if(prefix==='-')add('removed',line.slice(1),old++,null);
    else if(prefix==='+')add('added',line.slice(1),null,next++);
    else if(prefix===' ')add('context',line.slice(1),old++,next++);
    else add('marker',line,null,null);
   }
  }
  return {coarse:false,rows};
 }
 // Explicit bounded fallback: show every old/new line, never pretend the files are equal.
 const all=(text:string,operation:'removed'|'added')=>{
  if(!text)return;
  const lines=text.split('\n');const terminated=text.endsWith('\n');if(terminated)lines.pop();
  lines.forEach((line,index)=>add(operation,line,operation==='removed'?index+1:null,operation==='added'?index+1:null));
  if(!terminated)add('marker','\\ No newline at end of file',null,null);
 };
 all(before,'removed');all(after,'added');return {coarse:true,rows};
}
export function diffPage(diff:FileDiff,page:number):DiffRow[]{
 if(!Number.isInteger(page)||page<0)throw Error('Invalid diff page');
 return diff.rows.slice(page*DIFF_PAGE_ROWS,(page+1)*DIFF_PAGE_ROWS);
}
