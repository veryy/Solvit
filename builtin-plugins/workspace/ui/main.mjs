import {mountPage} from '../../../plugin-sdk/src/browser-page.mjs';
import {basicSetup} from 'codemirror';
import {EditorState,Compartment} from '@codemirror/state';
import {EditorView} from '@codemirror/view';
import {javascript} from '@codemirror/lang-javascript';
import {json} from '@codemirror/lang-json';
import {python} from '@codemirror/lang-python';
import {html} from '@codemirror/lang-html';
import {css} from '@codemirror/lang-css';

const root=document.getElementById('content'),footer=document.getElementById('actions');
mountPage(root,footer);
const baseUpdate=window.solvitPageUpdate,baseMessage=solvitHost.onmessage;
let state,meta,editor,key=null,source='',readOffset=0,pending=null,pendingBusy=false,editable=false,renderedToken=null;
let upload='',dirty=false,leaveArmed=false;
const editMode=new Compartment(),holder=document.createElement('section');holder.id='code-editor';
const strings={
 zh:['预览','编辑','未保存','正在读取','正在准备保存','再点返回将放弃修改','保存','文本超过256KiB','读取失败，请返回后重新打开'],
 en:['Preview','Edit','Unsaved','Loading','Preparing save','Tap Back again to discard changes','Save','Text exceeds 256KiB','Read failed. Go back and reopen.'],
 ja:['プレビュー','編集','未保存','読み込み中','保存準備中','もう一度戻ると変更を破棄','保存','256KiBを超えています','読み込み失敗。戻って再度開いてください。'],
 ko:['미리 보기','편집','저장 안 됨','불러오는 중','저장 준비 중','뒤로 다시 누르면 변경 삭제','저장','256KiB 초과','실패했습니다. 다시 여세요.'],
 es:['Vista previa','Editar','Sin guardar','Cargando','Preparando','Vuelve a pulsar Atrás para descartar','Guardar','Supera 256KiB','Vuelve atrás y abre de nuevo.'],
 fr:['Aperçu','Modifier','Non enregistré','Chargement','Préparation','Retour à nouveau pour abandonner','Enregistrer','Dépasse 256KiB','Revenez et ouvrez à nouveau.']
};
const labels=()=>strings[state?.locale?.split(/[-_]/)[0]]??strings.en;
const nodes=()=>{const result=[];const walk=n=>{result.push(n);(n.children??[]).forEach(walk)};[...state.view.nodes,...state.view.footer??[]].forEach(walk);return result};
const busy=()=>!!state?.busy||pending!==null;
function syncEditorMode() {
 if(!editor)return;
 const allowed=editable&&meta?.phase==='ready'&&!busy();
 if(editor.state.facet(EditorState.readOnly)===allowed)
  editor.dispatch({effects:editMode.reconfigure([EditorState.readOnly.of(!allowed),EditorView.editable.of(allowed)])});
}
function send(id,fields={}) {
 if(busy())return;
 const node=nodes().find(n=>n.id===id);if(!node||node.enabled===false)return;
 pending=state.token;pendingBusy=false;
 syncEditorMode();
 footer.querySelectorAll('button').forEach(b=>b.disabled=true);
 solvitHost.postMessage(JSON.stringify({type:'event',token:state.token,nodeId:id,fields}));
}
function language(path) {
 const ext=path.split('.').pop()?.toLowerCase();
 if(['js','jsx','mjs','cjs','ts','tsx'].includes(ext))return javascript({typescript:['ts','tsx'].includes(ext),jsx:['jsx','tsx'].includes(ext)});
 if(ext==='json')return json();if(ext==='py')return python();if(['html','htm'].includes(ext))return html();if(ext==='css')return css();
 return [];
}
function createEditor(path) {
 editor?.destroy();holder.replaceChildren();editable=meta.sha256===null;
 editor=new EditorView({parent:holder,state:EditorState.create({doc:source,extensions:[basicSetup,language(path),
  editMode.of([EditorState.readOnly.of(!editable),EditorView.editable.of(editable)]),
  EditorView.lineWrapping,EditorView.updateListener.of(update=>{
   if(update.docChanged){dirty=update.state.doc.toString()!==source;leaveArmed=false;status()}
  }),EditorView.theme({
   '&':{height:'100%',backgroundColor:'var(--surface)',color:'var(--onSurface)'},
   '.cm-scroller':{overflow:'auto',fontFamily:'monospace',fontSize:'14px'},
   '.cm-gutters':{backgroundColor:'var(--surfaceContainer)',color:'var(--onSurface)',borderColor:'var(--outline)'},
   '.cm-cursor':{borderLeftColor:'var(--primary)'},'.cm-selectionBackground':{backgroundColor:'var(--surfaceContainer)'},
   '.cm-panels':{backgroundColor:'var(--surfaceContainer)',color:'var(--onSurface)'},
   '.cm-searchMatch':{backgroundColor:'var(--surfaceContainer)',outline:'1px solid var(--primary)'}
  })]})});
 window.workspaceEditor=editor;
}
function status() {
 const line=document.getElementById('editor-status');if(!line)return;
 const t=labels();line.textContent=leaveArmed?t[5]:meta?.phase==='reading'?`${t[3]} ${readOffset}/${meta.total}`:
  meta?.phase==='uploading'?`${t[4]} ${meta.uploadOffset}/${upload.length}`:dirty?t[2]:editable?t[1]:t[0];
}
function nextTransfer() {
 if(busy()||!meta)return;
 if(meta.phase==='reading')send('readNext');
 if(meta.phase==='uploading'){
  if(meta.uploadOffset===upload.length){send('finishUpload');return}
  let end=Math.min(meta.uploadOffset+4096,upload.length);
  if(end<upload.length && /[\uD800-\uDBFF]/.test(upload[end-1]))end--;
  send('uploadChunk',{uploadOffset:String(meta.uploadOffset),content:upload.slice(meta.uploadOffset,end)});
 }
}
window.solvitPageUpdate=next=>{
 if(pending!==null){if(next.busy)pendingBusy=true;if(next.token!==pending||pendingBusy&&!next.busy)pending=null}
 state=next;baseUpdate(next);
 if(renderedToken===next.token){
  footer.querySelectorAll('button').forEach(b=>b.disabled=busy()||nodes().find(n=>n.id===b.dataset.node)?.enabled===false);
  const toggle=document.getElementById('toggle-edit');if(toggle)toggle.disabled=busy();
  syncEditorMode();status();if(!next.busy&&pending===null)queueMicrotask(nextTransfer);return;
 }
 renderedToken=next.token;
 const items=nodes(),info=items.find(n=>n.id==='document_meta');
 if(!info){
  if(!items.some(n=>n.id==='save_sha')){editor?.destroy();editor=null;key=null;window.workspaceEditor=null;dirty=false}
  root.classList.remove('editing');return;
 }
 meta=JSON.parse(info.text);
 if(key!==meta.editorId){key=meta.editorId;source='';readOffset=0;dirty=false;leaveArmed=false;editor?.destroy();editor=null}
 if(['ready','reading'].includes(meta.phase)&&meta.offset===readOffset && readOffset<meta.total){
  const text=items.find(n=>n.id==='document_chunk')?.text??'';
  if(text.length!==meta.nextOffset-meta.offset)throw Error('Incomplete file chunk');
  source+=text;readOffset=meta.nextOffset;
 }
 root.classList.add('editing');
 for(const id of ['document_meta','document_chunk','readNext','uploadChunk','finishUpload'])root.querySelector(`[data-node="${id}"]`)?.remove();
 for(const id of ['content','uploadOffset','uploadTotal'])document.getElementById(`field-${id}`)?.closest('label')?.remove();
 const toolbar=document.createElement('div');toolbar.className='editor-toolbar';
 const line=document.createElement('span');line.id='editor-status';toolbar.append(line);
 if(meta.phase==='ready'){
  if(!editor)createEditor(meta.path);
  const toggle=document.createElement('button');toggle.id='toggle-edit';toggle.textContent=editable?labels()[0]:labels()[1];
  toggle.disabled=busy();toggle.onclick=()=>{
   editable=!editable;editor.dispatch({effects:editMode.reconfigure([EditorState.readOnly.of(!editable),EditorView.editable.of(editable)])});
   toggle.textContent=editable?labels()[0]:labels()[1];status();if(editable)editor.focus();
  };toolbar.append(toggle);
 }
 root.append(toolbar);if(editor)root.append(holder);syncEditorMode();status();
 const save=footer.querySelector('[data-node="prepareSave"]');
 if(save){save.textContent=labels()[6];save.disabled=busy()||meta.phase!=='ready';save.onclick=()=>{
  if(!editor||busy())return;
  upload=editor.state.doc.toString();const path=document.getElementById('field-path')?.value??meta.path;
  if(new TextEncoder().encode(upload).length>262144||upload.includes('\u0000')){line.textContent=labels()[7];return}
  send('prepareSave',{path,uploadTotal:String(upload.length)});
 }}
 const back=footer.querySelector('[data-node="backToList"]');if(back)back.onclick=()=>{
  if(dirty&&!leaveArmed){leaveArmed=true;status();return}send('backToList');
 };
 if(!next.busy&&pending===null)queueMicrotask(nextTransfer);
};
solvitHost.onmessage=event=>{
 pending=null;pendingBusy=false;baseMessage(event);
 const message=JSON.parse(event.data);
 if(message.type==='error'){const line=document.getElementById('editor-status');if(line)line.textContent=labels()[8]}
};
