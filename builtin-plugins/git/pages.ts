import type {PluginContext,PluginPageInput,PluginPageView,PageNode,PageHostAction} from '../../plugin-sdk/src/index';
import {workspacePage} from '../workspace/pages';
type Entry={path:string;staged:string|null;working:string|null;conflict:boolean};
type Status={branch:string|null;head:string|null;entries:Entry[];total:number};
type Review={reviewId:string;path:string;staged:boolean;total:number};
type Chunk={text:string;offset:number;nextOffset:number;total:number};
type Commit={id:string;message:string;author:string;timeSeconds:number};
type Button=Extract<PageNode,{kind:'button'}>;
const labels={
 zh:['Git 工作区','Changes','文件管理','历史','刷新','初始化仓库','未暂存','已暂存','冲突','查看差异','暂存此文件','取消暂存','提交暂存区','提交说明','作者','邮箱','审阅提交','确认','返回','上一页','下一页','无文件变化','读取中','操作失败：','操作已完成','原始文件字节暂存；不支持LFS过滤器。文件或暂存区变化时须重新审阅。'],
 en:['Git workspace','Changes','Files','History','Refresh','Initialize repository','Working tree','Staged','Conflict','View diff','Stage this file','Unstage','Commit staged files','Message','Author','Email','Review commit','Confirm','Back','Previous','Next','No changes','Loading','Operation failed: ','Completed','Stages raw bytes; LFS filters are unsupported. Re-review if files or the index change.'],
 ja:['Gitワークスペース','変更','ファイル','履歴','更新','リポジトリ初期化','未ステージ','ステージ済み','競合','差分表示','ステージ','取り消し','ステージをコミット','メッセージ','作者','メール','確認','確定','戻る','前へ','次へ','変更なし','読込中','失敗：','完了','生バイトをステージ。LFS非対応。変更時は再確認してください。'],
 ko:['Git 작업 공간','변경','파일','기록','새로 고침','저장소 초기화','작업 트리','스테이지','충돌','차이 보기','스테이지 추가','스테이지 취소','스테이지 커밋','메시지','작성자','이메일','커밋 검토','확인','뒤로','이전','다음','변경 없음','불러오는 중','실패: ','완료','원본 바이트를 사용합니다. LFS 미지원. 변경 후 다시 검토하세요.'],
 es:['Espacio Git','Cambios','Archivos','Historial','Actualizar','Inicializar','Árbol de trabajo','Preparado','Conflicto','Ver diferencias','Preparar archivo','Quitar','Confirmar preparados','Mensaje','Autor','Correo','Revisar commit','Confirmar','Volver','Anterior','Siguiente','Sin cambios','Cargando','Error: ','Completado','Bytes originales; sin filtros LFS. Revisa de nuevo tras cambios.'],
 fr:['Espace Git','Modifications','Fichiers','Historique','Actualiser','Initialiser','Copie de travail','Index','Conflit','Voir la différence','Ajouter à l’index','Retirer','Commit de l’index','Message','Auteur','E-mail','Vérifier le commit','Confirmer','Retour','Précédent','Suivant','Aucun changement','Chargement','Échec : ','Terminé','Octets bruts ; filtres LFS non pris en charge. Revérifiez après modification.']
};
export function gitPage(){
 let mode='changes',offset=0,status:Status|null=null,message='',review:Review|null=null,chunk:Chunk|null=null;
 let commits:Commit[]=[],pending:PageHostAction|null=null,author='',email='',commitMessage='';
 const files=workspacePage();
 return async(input:PluginPageInput,ctx:PluginContext):Promise<PluginPageView>=>{
  const t=labels[input.locale.split(/[-_]/)[0] as keyof typeof labels]??labels.en,api=ctx.gitPage!,event=input.event;
  const renderFiles=async(value:PluginPageInput)=>{
   const view=await files(value,ctx);
   if(!view.nodes.some(n=>n.id==='document_meta')&&!view.footer?.some(n=>n.hostAction))
    view.footer=[{id:'git_changes',kind:'button',text:t[1],action:'gitChanges'},...view.footer??[]];
   return view;
  };
  if(mode==='files'&&event?.action!=='gitChanges')return renderFiles(input);
  const refresh=async()=>{try{status=await api.query('status',{offset,limit:10}) as unknown as Status}catch(error){status=null;throw error}};
  const read=async(start:number)=>{if(!review)throw Error('Review missing');chunk=await api.query('diffChunk',{reviewId:review.reviewId,offset:start}) as unknown as Chunk;
   if(chunk.offset!==start||chunk.nextOffset-start!==chunk.text.length||chunk.total!==review.total)throw Error('Incomplete Git patch');};
  try{
   if(event){
    message='';const action=event.action;
    if(action==='gitFiles'){mode='files';return await renderFiles({...input,event:null})}
    if(action==='gitChanges'||action==='gitRefresh'){mode='changes';review=null;chunk=null;pending=null;await refresh()}
    else if(action==='gitPrevious'||action==='gitNext'){offset=Math.max(0,offset+(action==='gitNext'?10:-10));await refresh();mode='changes'}
    else if(action==='gitDiffWorking'||action==='gitDiffStaged'){
     const entry=status?.entries[Number(event.nodeId.split('_').pop())];if(!entry)throw Error('Changes list changed');
     review=await api.query('diff',{path:entry.path,staged:action==='gitDiffStaged'}) as unknown as Review;
     await read(0);mode='diff';
    }else if(action==='gitReadNext'&&review&&chunk&&chunk.nextOffset<review.total)await read(chunk.nextOffset);
    else if(action==='gitStage'&&review){pending={kind:'git.perform',operation:review.staged?'unstage':'stage',arguments:{reviewId:review.reviewId}};mode='confirm'}
    else if(action==='gitInitialize'){pending={kind:'git.perform',operation:'initialize',arguments:{}};mode='confirm'}
    else if(action==='gitCommit'){mode='commit';pending=null}
    else if(action==='gitReviewCommit'){
     commitMessage=(event.fields.git_message??'').trim();author=(event.fields.git_author??'').trim();email=(event.fields.git_email??'').trim();
     if(!commitMessage||commitMessage.length>4096||!author||author.length>80||!email||email.length>254)throw Error('Message, author and email are required');
     const value=await api.query('reviewCommit',{}) as unknown as {revisionId:string};
     pending={kind:'git.perform',operation:'commit',arguments:{revisionId:value.revisionId,message:commitMessage,author,email}};mode='confirm';
    }else if(action==='gitConfirm'&&pending){await api.perform();pending=null;review=null;chunk=null;mode='changes';await refresh();message=t[24]}
    else if(action==='gitHistory'){commits=await api.query('history',{limit:5}) as unknown as Commit[];mode='history'}
    else if(action==='gitBack'){pending=null;mode='changes';await refresh()}
   }else await refresh();
  }catch(error){message=t[23]+(error instanceof Error?error.message:String(error));}
  const nodes:PageNode[]=[],footer:Button[]=[],text=(id:string,value:string):PageNode=>({id,kind:'text',text:value}),
   button=(id:string,value:string,action=id):Button=>({id,kind:'button',text:value,action});
  if(message)nodes.push(text('git_error',Array.from(message).slice(0,1000).join('')));
  if(mode==='changes'){
   nodes.push(text('git_branch',`${status?.branch??'—'} · ${status?.head?.slice(0,8)??'—'}`));
   for(const [i,entry]of (status?.entries??[]).entries())nodes.push({id:`git_file_${i}`,kind:'card',children:[text(`git_path_${i}`,entry.path),
    text(`git_state_${i}`,[entry.staged?t[7]:null,entry.working?t[6]:null,entry.conflict?t[8]:null].filter(Boolean).join(' · ')),
    ...(entry.working||entry.conflict?[button(`git_working_${i}`,t[9]+' · '+t[6],'gitDiffWorking')]:[]),
    ...(entry.staged?[button(`git_staged_${i}`,t[9]+' · '+t[7],'gitDiffStaged')]:[])]});
   if(status?.total===0)nodes.push(text('git_empty',t[21]));
   if(offset>0)nodes.push(button('git_previous',t[19],'gitPrevious'));
   if(status&&offset+status.entries.length<status.total)nodes.push(button('git_next',t[20],'gitNext'));
   footer.push(button('git_files',t[2],'gitFiles'),button('git_history',t[3],'gitHistory'),button('git_refresh',t[4],'gitRefresh'),
    button('git_commit',t[12],'gitCommit'));
   if(!status)nodes.push(button('git_initialize',t[5],'gitInitialize'));
  }else if(mode==='diff'&&review&&chunk){
   nodes.push(text('git_selected',`${review.path} · ${review.staged?t[7]:t[6]}`),
    text('git_diff_meta',JSON.stringify({...review,...chunk,text:undefined})),text('git_patch_chunk',chunk.text),
    {...button('git_read',t[22],'gitReadNext'),enabled:chunk.nextOffset<review.total});
   footer.push(button('git_back',t[18],'gitBack'),{...button('git_stage',review.staged?t[11]:t[10],'gitStage'),enabled:chunk.nextOffset===review.total});
  }else if(mode==='commit'){
   nodes.push({id:'git_message',kind:'field',label:t[13],value:commitMessage,multiline:true},
    {id:'git_author',kind:'field',label:t[14],value:author},{id:'git_email',kind:'field',label:t[15],value:email});
   footer.push(button('git_back',t[18],'gitBack'),button('git_review',t[16],'gitReviewCommit'));
  }else if(mode==='confirm'&&pending){
   nodes.push(text('git_review',JSON.stringify(pending.arguments,null,2)),text('git_limit',t[25]));
   footer.push(button('git_back',t[18],'gitBack'),{...button('git_confirm',t[17],'gitConfirm'),hostAction:pending});
  }else if(mode==='history'){
   for(const [i,c]of commits.entries())nodes.push({id:`git_commit_${i}`,kind:'card',children:[text(`git_id_${i}`,c.id.slice(0,8)),text(`git_message_${i}`,c.message),text(`git_author_${i}`,c.author)]});
   footer.push(button('git_back',t[18],'gitBack'));
  }
  return {title:t[0],nodes,footer};
 };
}
