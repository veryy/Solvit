import {mountPage} from '../../../plugin-sdk/src/browser-page.mjs';
const root=document.getElementById('content');
mountPage(root,document.getElementById('actions'));
const update=window.solvitPageUpdate;
window.solvitPageUpdate=next=>{
 update(next);
 for(const node of next.view.nodes){
  if(node.kind!=='text')continue;
  const element=root.querySelector(`[data-node="${node.id}"]`);
  if(!element)continue;
  if(node.id==='filePath')element.className='path';
  else if(['incomplete','pending','coarse','missing','failure'].includes(node.id))element.className='notice';
  else if(node.id==='fileIdentity'){
   const identity=JSON.parse(node.text);element.className='identity';
   const labels={zh:['副本信息','文本','不存在','不可用','二进制','超限'],en:['Saved copies','Text','Absent','Unavailable','Binary','Oversized'],ja:['コピー情報','テキスト','不存在','利用不可','バイナリ','上限超過'],ko:['사본 정보','텍스트','없음','사용 불가','바이너리','크기 초과'],es:['Copias guardadas','Texto','Ausente','No disponible','Binario','Demasiado grande'],fr:['Copies enregistrées','Texte','Absent','Indisponible','Binaire','Trop grand']};
   const t=labels[next.locale?.split(/[-_]/)[0]]??labels.en;
   const details=document.createElement('details'),summary=document.createElement('summary'),info=document.createElement('p');
   summary.textContent=t[0];
   info.textContent=[identity.before,identity.after].map((value,i)=>`${i?'＋':'−'} ${t[['TEXT','ABSENT','UNAVAILABLE','BINARY','OVERSIZED'].indexOf(value.kind)+1]??t[3]} · ${value.bytes??'?'} B\nSHA-256 ${value.sha256??'—'}`).join('\n');
   details.append(summary,info);element.replaceChildren(details);
  }else if(node.id.startsWith('diffRow_')){
   const row=JSON.parse(node.text);
   if(!['added','removed','context','header','marker'].includes(row.operation))continue;
   element.className=`diff-row ${row.operation}`;
   const old=document.createElement('span'),after=document.createElement('span'),code=document.createElement('code');
   old.className=after.className='line-number';
   old.textContent=row.beforeLine===null?'':String(row.beforeLine);
   after.textContent=row.afterLine===null?'':String(row.afterLine);
   code.textContent=(row.continuation?'↪ ':row.operation==='added'?'+ ':row.operation==='removed'?'- ':'  ')+row.text;
   element.replaceChildren(old,after,code);
  }
 }
};
