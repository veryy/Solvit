import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
const pkg=JSON.parse(await readFile('../market-server/packages/dev.solvit.skills.solvit.json','utf8'));
const clone=x=>JSON.parse(JSON.stringify(x));
const source=body=>`---\nname: page-writing\ndescription: Clear writing for user tasks.\n---\n${body}`;
async function page(data=new Map(),documents=[]){
 const sandbox=vm.createContext({});vm.runInContext(pkg.source,sandbox,{timeout:1000});
 const saved=[];let conflict=false,view;
 const ctx={storage:{get:async key=>data.has(key)?clone(data.get(key)):null,set:async(key,value)=>{data.set(key,clone(value));return null},
  compareAndSet:async(key,expected,value)=>{if(conflict||JSON.stringify(data.get(key)??null)!==JSON.stringify(expected))return false;data.set(key,clone(value));return true}},
  documents:{open:async()=>documents.shift()??null,save:async text=>{saved.push(text);return true}}};
 await sandbox.solvitPlugin.activate(ctx);
 const render=async event=>view=clone(await sandbox.solvitPlugin.pages.main({locale:'en',event},ctx));await render(null);
 const nodes=()=>{const result=[];const visit=n=>{result.push(n);(n.children??[]).forEach(visit)};[...view.nodes,...view.footer??[]].forEach(visit);return result};
 const event=async(id,value,fields={})=>{const node=nodes().find(n=>n.id===id);assert.ok(node);await render({nodeId:id,action:node.action,fields:{...Object.fromEntries(nodes().filter(n=>n.kind==='field').map(n=>[n.id,n.value])),...fields},...(value===undefined?{}:{value})});return view};
 return {data,ctx,saved,event,nodes,get view(){return view},conflict:value=>conflict=value,
  tool:async(name,args={})=>clone(await sandbox.solvitPlugin.tools[name](args,ctx)),
  scoped:async()=>sandbox.solvitPlugin.beforeModel({userText:'writing'}, {...ctx,role:{id:'writer',name:'Writer',skillIds:['page-writing']}})};
}
test('Skills package carries an offline HTML page and keeps older packages unchanged',async()=>{
 assert.equal(pkg.manifest.apiVersion,11);assert.equal(pkg.manifest.version,'1.1.0');
 assert.equal(pkg.manifest.pages[0].entry,'ui/index.html');assert.ok(pkg.resources['ui/main.js']);
 const legacy=JSON.parse(await readFile('../market-server/packages/legacy/dev.solvit.skills-1.0.2.solvit.json','utf8'));
 assert.equal(legacy.manifest.apiVersion,3);assert.equal(legacy.manifest.version,'1.0.2');
});
test('file import previews without writing, round trips resources and reaches role-scoped model loading',async()=>{
 const script='throw Error("must not execute")';
 const files={'page-writing/SKILL.md':source('Use short paragraphs.'),'page-writing/references/style.md':'Concrete examples.','page-writing/scripts/example.js':script};
 const p=await page(new Map(),[{name:'writing.zip',files}]);const before=JSON.stringify([...p.data]);
 await p.event('import');assert.equal(JSON.stringify([...p.data]),before);assert.ok(p.nodes().some(n=>n.text==='Import preview'));
 await p.event('confirm_import');assert.equal((await p.tool('skills_load',{name:'page-writing'})).instructions,'Use short paragraphs.');
 const opened=p.nodes().find(n=>n.id.startsWith('open_')&&p.nodes().find(t=>t.id===n.id.replace('open_','name_'))?.text==='page-writing');
 await p.event(opened.id);await p.event('resource','scripts/example.js');assert.ok(p.nodes().some(n=>n.text===script));
 await p.event('export');const backup=JSON.parse(p.saved[0]);assert.equal(backup.format,1);assert.equal(backup.files['scripts/example.js'],script);
 const metadata=await p.scoped();assert.match(metadata,/page-writing/);assert.doesNotMatch(metadata,/Use short paragraphs|must not execute/);
 const restarted=await page(p.data,[{name:'backup.json',text:p.saved[0]}]);await restarted.event('import');
 assert.equal(restarted.nodes().find(n=>n.id==='confirm_import').enabled,false);
 await restarted.event('replacement','yes');await restarted.event('confirm_import');
 assert.deepEqual((await restarted.tool('skills_export',{name:'page-writing'})).files,backup.files);
});
test('cancelled and malformed selection does not mutate data; same-name replacement needs a fresh review',async()=>{
 const p=await page(new Map(),[null,{name:'bad.zip',files:{'outside.txt':'x','page-writing/SKILL.md':source('Wrong package')}},{name:'SKILL.md',text:source('Original')},{name:'SKILL.md',text:source('Replacement')}]);
 const initial=JSON.stringify([...p.data]);await p.event('import');assert.equal(JSON.stringify([...p.data]),initial);
 await p.event('import');assert.equal(JSON.stringify([...p.data]),initial);assert.ok(p.nodes().some(n=>n.text?.startsWith('Invalid file')));
 await p.event('import');await p.event('confirm_import');await p.event('import');
 assert.equal(p.nodes().find(n=>n.id==='confirm_import').enabled,false);await p.event('replacement','yes');
 await p.tool('skills_import',{files:{'SKILL.md':source('Concurrent edit')},replace:true});
 await p.event('confirm_import');assert.match(p.nodes().find(n=>n.id==='message').text,/Data changed/);
 assert.equal((await p.tool('skills_load',{name:'page-writing'})).instructions,'Concurrent edit');
});
test('page storage conflicts cannot discard another writer and large Unicode instructions stay intact',async()=>{
 const body='A'.repeat(15999)+'😀'+'B'.repeat(14000);
 const p=await page(new Map(),[{name:'SKILL.md',text:source(body)}]);await p.event('import');
 p.conflict(true);const before=JSON.stringify([...p.data]);await p.event('confirm_import');
 assert.equal(JSON.stringify([...p.data]),before);assert.match(p.nodes().find(n=>n.id==='message').text,/Data changed/);
 p.conflict(false);await p.event('confirm_import');
 const open=p.nodes().find(n=>n.id.startsWith('open_')&&p.nodes().find(t=>t.id===n.id.replace('open_','name_'))?.text==='page-writing');await p.event(open.id);
 assert.equal(p.nodes().filter(n=>n.id.startsWith('body_')).map(n=>n.text).join(''),body);
 assert.ok(p.nodes().filter(n=>n.id.startsWith('body_')).every(n=>n.text.length<=16000));
 await p.event('delete');assert.ok(!(await p.tool('skills_list')).skills.some(s=>s.name==='page-writing'));
});
