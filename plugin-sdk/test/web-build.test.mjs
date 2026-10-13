import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,readFile,rm,symlink} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {buildPlugin} from '../scripts/build-plugin.mjs';

test('API10 bundles browser dependencies offline and refuses invalid or linked entries',async()=>{
  const dir=await mkdtemp(join(tmpdir(),'solvit-html-build-'));
  try {
    const entry=join(dir,'index.ts'),manifestFile=join(dir,'manifest.json'),output=join(dir,'output.json');
    await mkdir(join(dir,'ui'));
    await writeFile(entry,'export default {tools:{},pages:{main:()=>({title:"HTML",nodes:[]})}}');
    await writeFile(join(dir,'ui/index.html'),'<script src="main.js"></script>');
    await writeFile(join(dir,'ui/main.ts'),'import cytoscape from "cytoscape"; window.graph=cytoscape({headless:true});');
    const manifest={id:'test.html',name:'HTML',description:'Test',version:'1.0.0',apiVersion:10,permissions:[],dependencies:[],tools:[],pages:[{id:'main',title:'HTML',entry:'ui/index.html'}]};
    await writeFile(manifestFile,JSON.stringify(manifest));await buildPlugin(entry,manifestFile,output);
    const pkg=JSON.parse(await readFile(output,'utf8'));
    assert.equal(pkg.resources['ui/index.html'],'<script src="main.js"></script>');
    assert.ok(pkg.resources['ui/main.js'].length>100_000);assert.equal(pkg.resources['ui/main.ts'],undefined);
    const execution={...manifest,apiVersion:17,permissions:['workspace'],pages:[{...manifest.pages[0],context:'execution'}]};
    await writeFile(manifestFile,JSON.stringify(execution));await buildPlugin(entry,manifestFile,output);
    assert.equal(JSON.parse(await readFile(output,'utf8')).manifest.pages[0].context,'execution');
    for(const invalid of [{...execution,apiVersion:16},{...execution,permissions:[]},
      {...execution,pages:[{...execution.pages[0],context:'foreign'}]}]){
      await writeFile(manifestFile,JSON.stringify(invalid));await assert.rejects(()=>buildPlugin(entry,manifestFile,output));
    }
    for(const value of ['../index.html','/ui/index.html','https://example.com/index.html','ui/%2e/index.html','ui/missing.html']){
      await writeFile(manifestFile,JSON.stringify({...manifest,pages:[{...manifest.pages[0],entry:value}]}));
      await assert.rejects(()=>buildPlugin(entry,manifestFile,output));
    }
    await writeFile(manifestFile,JSON.stringify({...manifest,apiVersion:9}));await assert.rejects(()=>buildPlugin(entry,manifestFile,output));
    await writeFile(manifestFile,JSON.stringify(manifest));
    await symlink(entry,join(dir,'ui/linked.js'));await assert.rejects(()=>buildPlugin(entry,manifestFile,output),/symlink/);
  }finally{await rm(dir,{recursive:true})}
});
