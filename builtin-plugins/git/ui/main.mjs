// Reuse the released file tree and CodeMirror editor, including dirty-document guards.
import '../../workspace/ui/main.mjs';
const update=window.solvitPageUpdate;
let reviewId=null,nextOffset=0,patch=null;
let progressTimer=null;
window.addEventListener('pagehide',()=>{clearTimeout(progressTimer);progressTimer=null});
window.solvitPageUpdate=state=>{
 clearTimeout(progressTimer);progressTimer=null;
 update(state);
 if(!state.busy&&state.view.nodes.some(n=>n.id==='git_transfer'))
  progressTimer=setTimeout(()=>{progressTimer=null;document.querySelector('[data-node="git_remote_poll"]')?.click()},750);
 if(!state.view.nodes.some(n=>n.id==='tree_meta')){
  const tree=document.getElementById('file-tree');tree.hidden=true;tree.classList.remove('open');
 }
 const items=state.view.nodes,metaNode=items.find(n=>n.id==='git_diff_meta');
 document.getElementById('content').classList.toggle('git-diff',!!metaNode);
 if(!metaNode){reviewId=null;nextOffset=0;patch=null;return}
 const meta=JSON.parse(metaNode.text),chunk=items.find(n=>n.id==='git_patch_chunk')?.text??'';
 if(reviewId!==meta.reviewId){reviewId=meta.reviewId;nextOffset=0;patch=document.createElement('pre');patch.id='git-patch'}
 for(const id of ['git_diff_meta','git_patch_chunk','git_read'])document.querySelector(`[data-node="${id}"]`)?.classList.add('git-protocol');
 if(meta.offset===nextOffset&&meta.nextOffset-meta.offset===chunk.length){patch.append(document.createTextNode(chunk));nextOffset=meta.nextOffset}
 document.getElementById('content').append(patch);
 if(!state.busy&&nextOffset<meta.total)queueMicrotask(()=>document.querySelector('[data-node="git_read"]')?.click());
};
