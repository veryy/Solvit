import {mountPage} from '../../../plugin-sdk/src/browser-page.mjs';
import {polishEnvironment} from './workbench.mjs';
// Bind the flex page to the measured WebView viewport, including native IME resize.
const fitViewport=()=>{document.body.style.height=`${Math.max(1,window.innerHeight)}px`};
window.addEventListener('resize',fitViewport);
fitViewport();requestAnimationFrame(fitViewport);
const content=document.getElementById('content'),footer=document.getElementById('actions');
mountPage(content,footer);
const update=window.solvitPageUpdate;
let timer,lastToken,lastStage;
window.solvitPageUpdate=state=>{
 clearTimeout(timer);
 fitViewport();requestAnimationFrame(fitViewport);
 const scroll=content.scrollTop,logScroll=content.querySelector('[data-node="log"]')?.scrollTop??0;
 const open=new Set(Array.from(content.querySelectorAll('details[open]'),node=>node.id));
 update(state);
 const metadata=state.view.nodes.find(node=>node.id==='environment_meta');
 const env=metadata?JSON.parse(metadata.text):null;
 if(env&&lastToken!==state.token){
  // Reparent the original host-backed buttons; disclosures never invoke an action.
  polishEnvironment(content,footer,state,env,open,env.stage==='FAILED'&&lastStage!=='FAILED');
  lastToken=state.token;lastStage=env.stage;
 }
 content.scrollTop=scroll;
 const log=content.querySelector('[data-node="log"]');if(log)log.scrollTop=logScroll;
 if(env?.busy&&!state.busy)timer=setTimeout(()=>{
  const refresh=document.querySelector('[data-node="refresh"]');
  if(refresh&&!refresh.disabled)refresh.click();
 },1000);
};
window.addEventListener('pagehide',()=>{clearTimeout(timer);window.removeEventListener('resize',fitViewport)});
