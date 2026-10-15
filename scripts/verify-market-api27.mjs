import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
// Run on the deployment server against a private before/next snapshot, never the live listener.
const root = resolve(process.argv[2] ?? '.') + '/';
const { createMarketServer: oldServer, readCatalog: oldCatalog } = await import(pathToFileURL(root+'before/server.mjs'));
const { createMarketServer: nextServer } = await import(pathToFileURL(root+'next/server.mjs'));
const old = oldServer({directory:root+'before/packages'});
const next = nextServer({directory:root+'next/packages'});
const sha=b=>createHash('sha256').update(b).digest('hex');
const evidence={oldApisUnchanged:[],compatiblePatchApis:[],historicalPackages:[],expectedCatalogs:[]};
try {
 await Promise.all([old,next].map(s=>new Promise(r=>s.listen(0,'127.0.0.1',r))));
 const bases=[old,next].map(s=>`http://127.0.0.1:${s.address().port}`);
 const get=async(base,path)=>{const r=await fetch(base+path);assert.equal(r.status,200);return Buffer.from(await r.arrayBuffer());};
 for(let api=1;api<=26;api++) {
  const catalogs=await Promise.all(bases.map(b=>get(b,`/api/v1/plugins?hostApi=${api}`).then(JSON.parse)));
  evidence.expectedCatalogs.push({api,catalog:catalogs[1]});
  if(api<=19){assert.deepEqual(catalogs[1],catalogs[0]);evidence.oldApisUnchanged.push(api);}
  else {
   assert.deepEqual(catalogs[1].plugins.filter(p=>p.id!=='dev.solvit.git'),catalogs[0].plugins.filter(p=>p.id!=='dev.solvit.git'));
   assert.equal(catalogs[1].plugins.find(p=>p.id==='dev.solvit.git').version,'1.2.6');
   assert.equal(catalogs[1].plugins.find(p=>p.id==='dev.solvit.workspace').version,api===26?'1.9.0':api===25?'1.8.0':api===24?'1.7.0':api===23?'1.6.0':api===22?'1.5.0':'1.4.0');
   evidence.compatiblePatchApis.push(api);
  }
 }
 const historical=await oldCatalog(root+'before/packages');
 assert.equal(historical.packages.size,48,'Unexpected historical version count; review live state');
 for(const [key,value] of historical.packages){
  const path=`/api/v1/packages/${key}`;
  const bytes=await Promise.all(bases.map(b=>get(b,path)));
  assert.deepEqual(bytes[0],value.bytes);assert.deepEqual(bytes[1],value.bytes);
  evidence.historicalPackages.push({key,bytes:value.bytes.length,sha256:sha(value.bytes)});
 }
 const latest=JSON.parse(await get(bases[1],'/api/v1/plugins?hostApi=27'));
 evidence.expectedCatalogs.push({api:27,catalog:latest});
 for(const [id,version,api] of [['workspace','1.10.0',27],['git','1.2.6',20]]){
  const p=latest.plugins.find(p=>p.id===`dev.solvit.${id}`);assert.equal(p.version,version);assert.equal(p.apiVersion,api);
  if(id==='workspace') assert.deepEqual(p.nativeServices,['workspace.files']);
  const b=await get(bases[1],p.downloadPath);assert.equal(sha(b),p.sha256);
  assert.deepEqual(b,await readFile(root+`next/packages/dev.solvit.${id}.solvit.json`));
  (evidence.currentPackages??=[]).push({id:p.id,version,api,bytes:b.length,sha256:sha(b)});
 }
 await writeFile(root+'verified.json',JSON.stringify(evidence,null,2)+'\n');
 console.log(JSON.stringify({oldApisUnchanged:evidence.oldApisUnchanged,compatiblePatchApis:evidence.compatiblePatchApis,historicalPackages:evidence.historicalPackages.length,currentPackages:evidence.currentPackages}));
} finally {
 // Node 18 can otherwise wait on keep-alive sockets after all byte checks finish.
 for(const server of [old,next])server.closeIdleConnections();
 await Promise.all([old,next].map(s=>new Promise(r=>s.close(r))));
}
