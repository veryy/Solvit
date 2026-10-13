import {test} from 'node:test';
import assert from 'node:assert/strict';
import {build} from 'esbuild';
import vm from 'node:vm';
const bundle=await build({entryPoints:['../builtin-plugins/workspace/diff.ts'],bundle:true,write:false,
 platform:'neutral',format:'iife',globalName:'diffModule',target:'es2020'});
const sandbox=vm.createContext({});vm.runInContext(bundle.outputFiles[0].text,sandbox);
const {fileDiff,diffPage,DIFF_PAGE_ROWS}=sandbox.diffModule;
test('full diff detects an edit beyond old preview and retains line coordinates',()=>{
 const old=Array.from({length:2000},(_,i)=>`unchanged ${i}`).join('\n')+'\n';
 const next=old.replace('unchanged 1998','CHANGED-TAIL');
 const result=fileDiff(old,next);
 assert.equal(result.coarse,false);
 assert.ok(result.rows.some(r=>r.operation==='removed'&&r.text==='unchanged 1998'&&r.beforeLine===1999));
 assert.ok(result.rows.some(r=>r.operation==='added'&&r.text==='CHANGED-TAIL'&&r.afterLine===1999));
 assert.equal(fileDiff(old,old).rows.length,0);
});
test('creation deletion CRLF and missing terminal newline remain explicit',()=>{
 assert.ok(fileDiff('','new\n').rows.some(r=>r.operation==='added'&&r.text==='new'));
 assert.ok(fileDiff('gone\n','').rows.some(r=>r.operation==='removed'&&r.text==='gone'));
 assert.ok(fileDiff('old\r\n','new\r\n').rows.some(r=>r.operation==='added'&&r.text==='new\r'));
 assert.ok(fileDiff('old','new\n').rows.some(r=>r.operation==='marker'&&r.text.includes('No newline')));
});
test('bounded coarse fallback retains all 4000 removed and added lines',()=>{
 const old=Array.from({length:4000},(_,i)=>`old ${i}`).join('\n')+'\n';
 const next=Array.from({length:4000},(_,i)=>`new ${i}`).join('\n')+'\n';
 const result=fileDiff(old,next,{maxEditLength:0});assert.equal(result.coarse,true);
 assert.equal(result.rows.filter(r=>r.operation==='removed').length,4000);
 assert.equal(result.rows.filter(r=>r.operation==='added').length,4000);
 let count=0;for(let p=0;p<Math.ceil(result.rows.length/DIFF_PAGE_ROWS);p++)count+=diffPage(result,p).length;
 assert.equal(count,result.rows.length);assert.equal(result.rows.at(-1).text,'new 3999');
});
test('single long Unicode and HTML-like lines paginate without truncation or surrogate damage',()=>{
 const text='x'.repeat(1023)+'🙂'.repeat(3000)+'<script>literal</script>';
 const result=fileDiff('',text);const additions=result.rows.filter(r=>r.operation==='added');
 assert.equal(additions.map(r=>r.text).join(''),text);
 assert.ok(additions.slice(1).every(r=>r.continuation));
 assert.ok(additions.every(r=>r.text.length<=1024&&!/[\uD800-\uDBFF]$/.test(r.text)&&! /^[\uDC00-\uDFFF]/.test(r.text)));
 const escaped=fileDiff('','\\"'.repeat(6000));
 assert.ok(Buffer.byteLength(JSON.stringify(diffPage(escaped,0)))<128*1024);
 assert.throws(()=>fileDiff('','\u0000'));assert.throws(()=>fileDiff('','x'.repeat(256*1024+1)));
 assert.throws(()=>diffPage(result,-1));
});
