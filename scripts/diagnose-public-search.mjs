import {createRequire} from 'node:module';
import {readFile,writeFile} from 'node:fs/promises';
const {chromium,devices}=createRequire(process.env.SOLVIT_BROWSER_PACKAGE+'/package.json')('playwright');
const source=await readFile('web-android/src/main/kotlin/dev/solvit/web/PageObservation.kt','utf8');
const observe=/val script = """([\s\S]*?)"""\.trimIndent\(\)/.exec(source)[1];
const release=await (await fetch('https://api.github.com/repos/JetBrains/kotlin/releases/latest')).json();
const queries=['Kotlin programming language official documentation','Python 官方文档 编程语言','Kotlin latest stable release '+release.tag_name.replace(/^v/,'')];
const origins=[['bing','https://www.bing.com/search?q='],['regional','https://cn.bing.com/search?q='],['google','https://www.google.com/search?q='],['ddg-lite','https://lite.duckduckgo.com/lite/?q=']];
const browser=await chromium.launch();const observations=[];
try {
 for(const mode of ['desktop','android']) {
  const context=await browser.newContext({...(mode==='android'?devices['Pixel 7']:{}),locale:'zh-CN'});
  for(const query of queries) for(const [engine,base] of origins) {
   const page=await context.newPage();const url=base+encodeURIComponent(query)+(engine==='regional'?'&FORM=HDRSC1':'');
   await page.route('**/*',route=>route.request().url().startsWith('https://')?route.continue():route.abort());
   try {
    await page.goto(url,{waitUntil:'load',timeout:7000});await page.waitForTimeout(500);
    const snapshot=JSON.parse(await page.evaluate(observe));
    observations.push({mode,engine,query,requested:url,snapshot});
    console.log(mode,engine,query,snapshot.url,snapshot.title,snapshot.links.length,snapshot.text.slice(0,100));
   }catch(error){observations.push({mode,engine,query,requested:url,final:page.url(),error:String(error).slice(0,500)});}
   finally{await page.close();}
  }
  await context.close();
 }
} finally {await writeFile('search-release-diagnosis.json',JSON.stringify({release:release.tag_name,observations},null,2));await browser.close();}
