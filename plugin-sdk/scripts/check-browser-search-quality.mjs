import { readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import vm from 'node:vm';
const require = createRequire(process.env.SOLVIT_BROWSER_PACKAGE + '/package.json');
const { chromium } = require('playwright');
const pkg = JSON.parse(await readFile(new URL('../../market-server/packages/dev.solvit.web-search.solvit.json', import.meta.url), 'utf8'));
const kotlin = await readFile(new URL('../../web-android/src/main/kotlin/dev/solvit/web/PageObservation.kt', import.meta.url), 'utf8');
const script = /val script = """([\s\S]*?)"""\.trimIndent\(\)/.exec(kotlin)?.[1];
if (!script || pkg.manifest.apiVersion !== 6) throw new Error('Expected API 6 search and actual Android DOM observer');
const sandbox = vm.createContext({}); vm.runInContext(pkg.source, sandbox, {timeout:1000});
const browser = await chromium.launch({headless:true});
const context = await browser.newContext({locale:'zh-CN'});
const observations = [], checks = [];
const ctx = {settings:{get:async()=>null},http:{request:async options=>{
  const response=await fetch(options.url,{headers:options.headers,signal:AbortSignal.timeout(options.timeoutMs??6000)});
  return {status:response.status,headers:Object.fromEntries(response.headers),body:await response.text()};
}},web:{visit:async url=>{
  const page=await context.newPage();
  await page.route('**/*',route=>route.request().url().startsWith('https://') ? route.continue() : route.abort());
  try {
    await page.goto(url,{waitUntil:'load',timeout:7000});
    await page.waitForTimeout(500);
    const snapshot=JSON.parse(await page.evaluate(script));
    observations.push({url,finalUrl:snapshot.url,title:snapshot.title,links:snapshot.links.length,textSample:snapshot.text.slice(0,2000),dom:await page.evaluate(()=>({bodyText:document.body.innerText.slice(0,2000),bodyLinks:document.querySelectorAll('a[href]').length,results:document.querySelectorAll('li.b_algo').length,main:document.querySelector('main,article,[role="main"]')?.outerHTML.slice(0,2000)}))});
    return snapshot;
  } finally {await page.close();}
}}};
try {
  for (const item of [
    {query:'Kotlin programming language official documentation',host:'kotlinlang.org',term:/kotlin/i},
    {query:'Python 官方文档 编程语言',host:'python.org',term:/python/i},
    {query:'Kotlin latest stable release version',host:'kotlinlang.org',term:/kotlin/i},
  ]) {
    const result=await sandbox.solvitPlugin.tools.search({query:item.query,limit:5},ctx);
    const record={query:item.query,provider:result.provider,results:result.results??[],error:result.error}; checks.push(record);
    if (result.error) throw new Error('Search unavailable: '+item.query);
    const relevant=result.results.find(source=>{
      const host=new URL(source.url).hostname;
      return (host===item.host||host.endsWith('.'+item.host))&&item.term.test(source.title+' '+source.snippet);
    });
    if (!relevant) throw new Error('Top five sources lack the expected official source: '+item.query);
    const page=await sandbox.solvitPlugin.tools.read_page({url:relevant.url},ctx);
    record.page={url:page.url,title:page.title,characters:page.text?.length??0,truncated:page.truncated,error:page.error};
    if (page.error||page.text?.length<100||!item.term.test(page.text)) throw new Error('Official source body unavailable: '+relevant.url);
  }
  console.log('Public search quality: bilingual/release queries return relevant official sources and readable bodies. Model answer attribution remains unverified.');
} finally {
  await writeFile('browser-search-evidence.json',JSON.stringify({checks,observations},null,2));
  await context.close(); await browser.close();
}
