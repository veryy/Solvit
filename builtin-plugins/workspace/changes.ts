import type {PluginContext,PluginPageInput,PluginPageView,PageNode} from '../../plugin-sdk/src/index';
import {fileDiff,diffPage,DIFF_PAGE_ROWS,type FileDiff} from './diff';
type Observation={kind:string;sha256:string|null;bytes:number|null;captured:boolean};
type Change={index:number;path:string;kind:string;before:Observation;after:Observation};
type Review={complete:boolean;pending:boolean;beforeAt:number|null;afterAt:number|null;changes:Change[]};
type Chunk={available:boolean;kind:string;sha256:string|null;text:string|null;offset:number;nextOffset:number;total:number|null};
const labels={
 zh:['文件变更','已保存的执行副本；不会读取当前文件','扫描不完整，未观察内容不能视为未变更','存在没有后续观察的操作','已观察范围内没有变化','查看','返回','上一页','下一页','副本不可用：旧记录、敏感内容、大小或存储预算限制可能没有全文','差异计算超时，完整显示整段删除与添加','读取失败，请重试','重试','没有逐行变化'],
 en:['File changes','Saved execution copies; current files are never read','Incomplete scan; unobserved files are unknown','An operation has no subsequent observation','No changes in the observed scope','View','Back','Previous','Next','Copy unavailable: old records, credentials, size or storage limits may omit full content','Diff computation timed out; showing every old and new line','Read failed; retry','Retry','No line changes'],
 ja:['ファイル変更','保存済みの実行コピー。現在のファイルは読みません','走査が不完全。未観測の内容は不明です','操作後の観測がありません','観測範囲内に変更なし','表示','戻る','前へ','次へ','コピーなし：旧記録・機密情報・サイズや保存上限で省略される場合があります','差分計算がタイムアウト。削除・追加の全行を表示','読み取り失敗。再試行してください','再試行','行単位の変更なし'],
 ko:['파일 변경','저장된 실행 사본만 표시합니다','불완전한 검사: 관찰하지 않은 파일은 알 수 없음','작업 이후 관찰이 없음','관찰 범위에 변경 없음','보기','뒤로','이전','다음','사본 없음: 이전 기록, 비밀 정보 또는 크기/저장 제한','계산 시간 초과: 이전/새 파일의 모든 줄 표시','읽기 실패: 다시 시도하세요','다시 시도','줄 변경 없음'],
 es:['Cambios de archivos','Copias guardadas; nunca se leen archivos actuales','Escaneo incompleto; archivos no observados son desconocidos','Hay una operación sin observación posterior','Sin cambios en el alcance observado','Ver','Volver','Anterior','Siguiente','Copia no disponible: registros antiguos, credenciales o límites de tamaño/almacenamiento','Tiempo agotado; se muestran todas las líneas anteriores y nuevas','Error de lectura; reintenta','Reintentar','Sin cambios de líneas'],
 fr:['Modifications des fichiers','Copies enregistrées ; aucun fichier actuel lu','Analyse incomplète ; fichiers non observés inconnus','Une opération sans observation ultérieure','Aucun changement dans la portée observée','Voir','Retour','Précédent','Suivant','Copie indisponible : anciens enregistrements, secrets ou limites de taille/stockage','Calcul expiré ; toutes les lignes anciennes et nouvelles affichées','Échec de lecture ; réessayez','Réessayer','Aucune ligne modifiée'],
};
/** The HTML view consumes typed row JSON as literal text. All diff decisions live in this TS plugin. */
export function changesPage(){
 let review:Review|null=null,selected:Change|null=null,diff:FileDiff|null=null,page=0,listPage=0,failed=false,missing=false;
 return async({locale,event}:PluginPageInput,ctx:PluginContext):Promise<PluginPageView>=>{
  const t=labels[locale.split(/[-_]/)[0] as keyof typeof labels]??labels.en;
  const api=ctx.workspacePage;if(!api)throw Error('Immutable execution access is unavailable');
  const loadText=async(change:Change,side:'before'|'after')=>{
   const expected=change[side];let offset=0,total:number|null=null,body='';
   for(let i=0;i<=64;i++){
    const chunk=await api.query('changeContent',{index:change.index,side,offset}) as unknown as Chunk;
    if(!chunk.available)return null;
    if(chunk.kind!==expected.kind||chunk.sha256!==expected.sha256||typeof chunk.text!=='string'||
       !Number.isInteger(chunk.total)||chunk.total===null||chunk.total<0||chunk.total>256*1024||
       chunk.offset!==offset||chunk.nextOffset!==offset+chunk.text.length||chunk.text.length>4096||
       chunk.nextOffset>chunk.total||total!==null&&chunk.total!==total)throw Error('Invalid immutable chunk');
    total=chunk.total;body+=chunk.text;
    if(chunk.nextOffset===total)return body;
    if(chunk.nextOffset<=offset)throw Error('Immutable chunk made no progress');
    offset=chunk.nextOffset;
   }
   throw Error('Immutable copy exceeded its chunk budget');
  };
  const open=async()=>{
   if(!selected)return;
   diff=null;missing=false;failed=false;page=0;
   const before=await loadText(selected,'before'),after=await loadText(selected,'after');
   if(before===null||after===null)missing=true;
   else diff=fileDiff(before,after);
  };
  failed=false;
  try{
   if(!review)review=await api.query('changes') as unknown as Review;
   if(event){
    if(event.action==='open'){
     const index=Number(event.nodeId.slice(7));selected=review.changes.find(c=>c.index===index)??null;
     if(!selected)throw Error('Unknown file');await open();
    }else if(event.action==='retry')await open();
    else if(event.action==='back'){selected=null;diff=null;missing=false;failed=false}
    else if(event.action==='previous')page=Math.max(0,page-1);
    else if(event.action==='next'&&diff)page=Math.min(Math.max(0,Math.ceil(diff.rows.length/DIFF_PAGE_ROWS)-1),page+1);
    else if(event.action==='listPrevious')listPage=Math.max(0,listPage-1);
    else if(event.action==='listNext')listPage=Math.min(Math.max(0,Math.ceil(review.changes.length/8)-1),listPage+1);
   }
  }catch{failed=true}
  const nodes:PageNode[]=[],footer:Extract<PageNode,{kind:'button'}>[]=[];
  const text=(id:string,value:string):PageNode=>({id,kind:'text',text:value});
  const button=(id:string,value:string,action=id):Extract<PageNode,{kind:'button'}>=>({id,kind:'button',text:value,action});
  nodes.push(text('scope',t[1]));
  if(review&&!review.complete)nodes.push(text('incomplete',t[2]));
  if(review?.pending)nodes.push(text('pending',t[3]));
  if(selected){
   nodes.push(text('filePath',selected.path),text('fileIdentity',JSON.stringify({before:selected.before,after:selected.after})));
   footer.push(button('back',t[6]));
   if(missing)nodes.push(text('missing',t[9]));
   if(diff){
    if(diff.coarse)nodes.push(text('coarse',t[10]));
    if(!diff.rows.length)nodes.push(text('emptyDiff',t[13]));
    diffPage(diff,page).forEach((row,i)=>nodes.push(text(`diffRow_${i}`,JSON.stringify(row))));
    nodes.push(text('pageNumber',`${page+1} / ${Math.max(1,Math.ceil(diff.rows.length/DIFF_PAGE_ROWS))}`));
    if(page>0)footer.push(button('previous',t[7]));
    if((page+1)*DIFF_PAGE_ROWS<diff.rows.length)footer.push(button('next',t[8]));
   }
  }else if(review){
   if(!review.changes.length)nodes.push(text('empty',t[4]));
   review.changes.slice(listPage*8,(listPage+1)*8).forEach(c=>nodes.push(button(`change_${c.index}`,c.path.length>96?c.path.slice(0,95)+'…':c.path,'open')));
   if(listPage>0)footer.push(button('listPrevious',t[7]));
   if((listPage+1)*8<review.changes.length)footer.push(button('listNext',t[8]));
  }
  if(failed){nodes.push(text('failure',t[11]));footer.push(button('retry',t[12]))}
  return {title:t[0],nodes,footer};
 };
}
