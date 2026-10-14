import {mountPage} from '../../../plugin-sdk/src/browser-page.mjs';
// WebView can initially resolve CSS viewport/percentage heights to zero even
// after innerHeight becomes available. Keep this page tied to its actual viewport.
const fitViewport=()=>{document.body.style.height=`${Math.max(1,window.innerHeight)}px`};
window.addEventListener('resize',fitViewport);
fitViewport();requestAnimationFrame(fitViewport);
const content=document.getElementById('content');
mountPage(content,document.getElementById('actions'));
const update=window.solvitPageUpdate;
let timer;
window.solvitPageUpdate=state=>{
 clearTimeout(timer);
 fitViewport();requestAnimationFrame(fitViewport);
 const scroll=content.scrollTop;
 update(state);
 content.scrollTop=scroll;
 const metadata=state.view.nodes.find(node=>node.id==='environment_meta');
 if(metadata&&JSON.parse(metadata.text).busy&&!state.busy)timer=setTimeout(()=>{
  const refresh=document.querySelector('[data-node="refresh"]');
  if(refresh&&!refresh.disabled)refresh.click();
 },1000);
};
window.addEventListener('pagehide',()=>{clearTimeout(timer);window.removeEventListener('resize',fitViewport)});
