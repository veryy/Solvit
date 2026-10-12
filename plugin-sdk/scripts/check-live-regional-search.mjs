import {readFile,writeFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const require=createRequire(process.env.SOLVIT_BROWSER_PACKAGE+'/package.json');
const {chromium}=require('playwright');
const pkg=JSON.parse(await readFile(new URL('../../market-server/packages/dev.solvit.web-search.solvit.json',import.meta.url),'utf8'));
const kotlin=await readFile(new URL('../../web-android/src/main/kotlin/dev/solvit/web/PageObservation.kt',import.meta.url),'utf8');
const script=/val script = """([\s\S]*?)"""\.trimIndent\(\)/.exec(kotlin)?.[1];
assert.ok(script);
const sandbox=vm.createContext({});vm.runInContext(pkg.source,sandbox,{timeout:1000});
const browser=await chromium.launch({headless:true});
const context=await browser.newContext({locale:'zh-CN'});
let injected=0,result,source;
const observations=[];
const ctx={settings:{get:async()=>null},http:{request:async()=>{throw new Error('This acceptance requires the live regional DOM, not RSS')}},web:{visit:async url=>{
  if(url.startsWith('https://www.bing.com/search?')){injected++;throw new Error('Controlled primary-origin outage')}
  const page=await context.newPage();
  await page.route('**/*',route=>route.request().url().startsWith('https://')?route.continue():route.abort());
  try{
    await page.goto(url,{waitUntil:'load',timeout:7000});await page.waitForTimeout(500);
    const snapshot=JSON.parse(await page.evaluate(script));
    observations.push({requested:url,final:snapshot.url,title:snapshot.title,searchResults:snapshot.searchResults,links:snapshot.links.length,characters:snapshot.text.length});
    return snapshot;
  }finally{await page.close()}
}}};
try{
  result=await sandbox.solvitPlugin.tools.search({query:'Kotlin programming language official documentation',limit:5},ctx);
  assert.equal(injected,1);assert.equal(result.provider,'bing-browser');assert.ok(result.results?.length);
  assert.ok(observations.some(x=>x.requested.startsWith('https://cn.bing.com/search?')&&x.searchResults===true));
  source=await sandbox.solvitPlugin.tools.read_page({url:result.results[0].url},ctx);
  assert.ok(!source.error&&source.text?.length>=100&&/kotlin/i.test(source.text));
  console.log('Controlled primary outage: real regional browser results and source body passed. This does not guarantee external service availability.');
}finally{
  await writeFile('browser-regional-evidence.json',JSON.stringify({injectedPrimaryFailure:true,injected,result,source:source?{url:source.url,title:source.title,characters:source.text?.length,error:source.error}:null,observations},null,2));
  await context.close();await browser.close();
}
