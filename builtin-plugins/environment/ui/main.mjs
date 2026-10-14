import {mountPage} from '../../../plugin-sdk/src/browser-page.mjs';
const content=document.getElementById('content');
mountPage(content,document.getElementById('actions'));
const update=window.solvitPageUpdate;
let timer;
window.solvitPageUpdate=state=>{
 clearTimeout(timer);
 const scroll=content.scrollTop;
 update(state);
 content.scrollTop=scroll;
 const metadata=state.view.nodes.find(node=>node.id==='environment_meta');
 if(metadata&&JSON.parse(metadata.text).busy&&!state.busy)timer=setTimeout(()=>{
  const refresh=document.querySelector('[data-node="refresh"]');
  if(refresh&&!refresh.disabled)refresh.click();
 },1000);
};
window.addEventListener('pagehide',()=>clearTimeout(timer));
