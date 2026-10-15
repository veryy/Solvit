import type {PluginContext,PluginPageInput,PluginPageView,PageNode,PageHostAction} from '../../plugin-sdk/src/index';
import {workspacePage} from '../workspace/pages';
type Entry={path:string;staged:string|null;working:string|null;conflict:boolean};
type Status={branch:string|null;head:string|null;entries:Entry[];total:number};
type Review={reviewId:string;path:string;staged:boolean;total:number};
type Chunk={text:string;offset:number;nextOffset:number;total:number};
type Commit={id:string;message:string;author:string;timeSeconds:number};
type Button=Extract<PageNode,{kind:'button'}>;
type CloneEntry={id:string;phase:string;status:string;url:string;branch:string;head:string|null;createdAt:number};
type CloneInventory={entries:CloneEntry[];nextOffset:number|null;incomplete:boolean;unreadable:number};
type CloneReview={reviewId:string;entry:CloneEntry;canResume:boolean;canRestart:boolean;files:number;bytes:number;installedFiles:number;problem:string|null};
const recoveryLabels={
 zh:['克隆恢复','查看中断记录','审阅','继续安装 / 确认已发布','重新下载','放弃私有暂存','仅显示已保存的事实，不会自动重试或回滚工作区。','没有待处理的克隆记录','文件 / 字节 / 已安装文件','有记录无法读取或列表未完整；未知目录不会自动清理。','来源','继续安装不联网；重新下载需要再次确认；放弃只清私有暂存，工作区文件和修改均保留。'],
 en:['Clone recovery','Inspect interrupted clones','Review','Resume / acknowledge publication','Download again','Discard private staging','Saved facts only. No automatic retries or workspace rollback.','No pending clones','Files / bytes / installed files','Some records are unreadable or omitted. Unknown directories are not deleted.','Source','Resume works offline. Downloading again requires confirmation. Discard removes private staging only; workspace files and edits remain.'],
 ja:['クローン復旧','中断記録を見る','確認','再開 / 公開済みを確認','再ダウンロード','非公開の一時データを破棄','保存済みの事実のみ表示。自動再試行や作業フォルダの巻き戻しは行いません。','保留中のクローンはありません','ファイル / バイト / 配置済み','読めない記録または省略があります。不明なフォルダは削除しません。','取得元','再開はオフラインです。再ダウンロードは確認が必要です。破棄しても作業ファイルと編集は残ります。'],
 ko:['복제 복구','중단 기록 보기','검토','재개 / 게시 확인','다시 다운로드','비공개 임시 데이터 폐기','저장된 사실만 표시하며 자동 재시도나 작업 폴더 되돌리기는 하지 않습니다.','대기 중인 복제 없음','파일 / 바이트 / 설치된 파일','읽을 수 없거나 생략된 기록이 있습니다. 알 수 없는 폴더는 삭제하지 않습니다.','원본','재개는 오프라인입니다. 다시 다운로드하려면 확인이 필요합니다. 폐기해도 작업 파일과 편집은 유지됩니다.'],
 es:['Recuperar clonación','Ver clonaciones interrumpidas','Revisar','Continuar / confirmar publicación','Descargar de nuevo','Descartar datos privados','Solo hechos guardados. Sin reintentos ni restauraciones automáticas.','No hay clonaciones pendientes','Archivos / bytes / instalados','Hay registros ilegibles u omitidos. No se borran carpetas desconocidas.','Origen','Continuar funciona sin red. Volver a descargar requiere confirmación. Descartar conserva archivos y cambios del espacio.'],
 fr:['Reprendre un clonage','Voir les clonages interrompus','Vérifier','Reprendre / confirmer la publication','Télécharger à nouveau','Supprimer les données privées','Faits enregistrés uniquement. Aucun nouvel essai ni retour arrière automatique.','Aucun clonage en attente','Fichiers / octets / installés','Des entrées sont illisibles ou omises. Les dossiers inconnus ne sont pas supprimés.','Source','Reprendre fonctionne hors ligne. Un nouveau téléchargement nécessite confirmation. Supprimer conserve les fichiers et modifications de travail.']
};
const remoteLabels={
 zh:['远程仓库','HTTPS 仓库地址','分支','下载远端对象','审阅拉取','审阅推送','正在传输','更新进度','取消传输','应用合并','推送审阅的提交','本地提交','远端提交','请先提交本地变化，再拉取；冲突解决后需暂存并提交。'],
 en:['Remote','HTTPS repository URL','Branch','Fetch objects','Review pull','Review push','Transferring','Update progress','Cancel transfer','Apply merge','Push reviewed commit','Local commit','Remote commit','Commit local changes before pulling. Stage and commit resolved conflicts.'],
 ja:['リモート','HTTPSリポジトリURL','ブランチ','取得','プルを確認','プッシュを確認','転送中','進捗更新','転送取消','マージ適用','確認済みコミットを送信','ローカル','リモート','プル前に変更をコミット。競合解決後はステージしてコミット。'],
 ko:['원격 저장소','HTTPS 저장소 URL','브랜치','가져오기','풀 검토','푸시 검토','전송 중','진행 확인','전송 취소','병합 적용','검토한 커밋 푸시','로컬 커밋','원격 커밋','풀 전에 변경을 커밋하세요. 충돌 해결 후 스테이지 및 커밋하세요.'],
 es:['Remoto','URL HTTPS','Rama','Descargar objetos','Revisar pull','Revisar push','Transfiriendo','Ver progreso','Cancelar','Aplicar merge','Enviar commit revisado','Commit local','Commit remoto','Confirma cambios antes de pull. Resuelve conflictos, prepara y confirma.'],
 fr:['Distant','URL HTTPS','Branche','Récupérer les objets','Vérifier pull','Vérifier push','Transfert','Actualiser','Annuler','Appliquer merge','Envoyer le commit vérifié','Commit local','Commit distant','Committez avant pull. Résolvez les conflits, puis indexez et committez.']
};
const cloneLabels={zh:'克隆到空工作目录',en:'Clone into empty directory',ja:'空の作業フォルダにクローン',ko:'빈 작업 폴더에 복제',es:'Clonar en carpeta vacía',fr:'Cloner dans un dossier vide'};
const credentialLabels={zh:'仓库凭据',en:'Repository credentials',ja:'リポジトリ認証情報',ko:'저장소 자격 증명',es:'Credenciales del repositorio',fr:'Identifiants du dépôt'};
const labels={
 zh:['Git 工作区','Changes','文件管理','历史','刷新','初始化仓库','未暂存','已暂存','冲突','查看差异','暂存此文件','取消暂存','提交暂存区','提交说明','作者','邮箱','审阅提交','确认','返回','上一页','下一页','无文件变化','读取中','操作失败：','操作已完成','原始文件字节暂存；不支持LFS过滤器。文件或暂存区变化时须重新审阅。'],
 en:['Git workspace','Changes','Files','History','Refresh','Initialize repository','Working tree','Staged','Conflict','View diff','Stage this file','Unstage','Commit staged files','Message','Author','Email','Review commit','Confirm','Back','Previous','Next','No changes','Loading','Operation failed: ','Completed','Stages raw bytes; LFS filters are unsupported. Re-review if files or the index change.'],
 ja:['Gitワークスペース','変更','ファイル','履歴','更新','リポジトリ初期化','未ステージ','ステージ済み','競合','差分表示','ステージ','取り消し','ステージをコミット','メッセージ','作者','メール','確認','確定','戻る','前へ','次へ','変更なし','読込中','失敗：','完了','生バイトをステージ。LFS非対応。変更時は再確認してください。'],
 ko:['Git 작업 공간','변경','파일','기록','새로 고침','저장소 초기화','작업 트리','스테이지','충돌','차이 보기','스테이지 추가','스테이지 취소','스테이지 커밋','메시지','작성자','이메일','커밋 검토','확인','뒤로','이전','다음','변경 없음','불러오는 중','실패: ','완료','원본 바이트를 사용합니다. LFS 미지원. 변경 후 다시 검토하세요.'],
 es:['Espacio Git','Cambios','Archivos','Historial','Actualizar','Inicializar','Árbol de trabajo','Preparado','Conflicto','Ver diferencias','Preparar archivo','Quitar','Confirmar preparados','Mensaje','Autor','Correo','Revisar commit','Confirmar','Volver','Anterior','Siguiente','Sin cambios','Cargando','Error: ','Completado','Bytes originales; sin filtros LFS. Revisa de nuevo tras cambios.'],
 fr:['Espace Git','Modifications','Fichiers','Historique','Actualiser','Initialiser','Copie de travail','Index','Conflit','Voir la différence','Ajouter à l’index','Retirer','Commit de l’index','Message','Auteur','E-mail','Vérifier le commit','Confirmer','Retour','Précédent','Suivant','Aucun changement','Chargement','Échec : ','Terminé','Octets bruts ; filtres LFS non pris en charge. Revérifiez après modification.']
};
export function gitPage(){
 let mode='changes',offset=0,status:Status|null=null,message='',notice=false,review:Review|null=null,chunk:Chunk|null=null;
 let commits:Commit[]=[],pending:Extract<PageHostAction,{kind:'git.perform'}>|null=null,author='',email='',commitMessage='';
 let remoteUrl='',remoteBranch='main',operationId='',remoteTarget='',remoteReview:{reviewId:string;localHead:string;remoteHead:string}|null=null;
 let remoteProgress='';
 let clones:CloneInventory|null=null,cloneReview:CloneReview|null=null,cloneOffset=0,cloneError='';
 const files=workspacePage(false);
 return async(input:PluginPageInput,ctx:PluginContext):Promise<PluginPageView>=>{
  const t=labels[input.locale.split(/[-_]/)[0] as keyof typeof labels]??labels.en,api=ctx.gitPage!,event=input.event;
  const rt=remoteLabels[input.locale.split(/[-_]/)[0] as keyof typeof remoteLabels]??remoteLabels.en;
  const cloneLabel=cloneLabels[input.locale.split(/[-_]/)[0] as keyof typeof cloneLabels]??cloneLabels.en;
  const credentialLabel=credentialLabels[input.locale.split(/[-_]/)[0] as keyof typeof credentialLabels]??credentialLabels.en;
  const cr=recoveryLabels[input.locale.split(/[-_]/)[0] as keyof typeof recoveryLabels]??recoveryLabels.en;
  const loadClones=async()=>{
   cloneError='';
   try{clones=await api.query('cloneInstalls',{offset:cloneOffset,limit:5}) as unknown as CloneInventory}
   catch(error){clones=null;cloneError=Array.from(error instanceof Error?error.message:String(error)).slice(0,1000).join('')}
  };
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
    message='';notice=false;const action=event.action;
    if(action==='gitFiles'){mode='files';return await renderFiles({...input,event:null})}
    if(action==='gitClones'){mode='clones';cloneOffset=0;cloneReview=null;pending=null;await loadClones()}
    else if(action==='gitClonePrevious'||action==='gitCloneNext'){
     cloneOffset=action==='gitCloneNext'?(clones?.nextOffset??cloneOffset):Math.max(0,cloneOffset-5);mode='clones';await loadClones();
    }else if(action==='gitCloneReview'){
     const entry=clones?.entries[Number(event.nodeId.split('_').pop())];if(!entry)throw Error('Clone record changed');
     cloneReview=null;pending=null;
     cloneReview=await api.query('reviewCloneInstall',{id:entry.id}) as unknown as CloneReview;mode='clone-review';
    }else if(action==='gitCloneResume'||action==='gitCloneRestart'||action==='gitCloneDiscard'){
     if(!cloneReview)throw Error('Review missing');
     if(action==='gitCloneResume'&&!cloneReview.canResume||action==='gitCloneRestart'&&!cloneReview.canRestart)throw Error('Clone review does not allow this operation');
     remoteTarget=action==='gitCloneResume'?'resumeCloneInstall':action==='gitCloneRestart'?'restartCloneDownload':'discardCloneInstall';
     pending=remoteTarget==='discardCloneInstall'?{kind:'git.perform',operation:'discardCloneInstall',arguments:{reviewId:cloneReview.reviewId}}:
      {kind:'git.perform',operation:'startRemote',arguments:{operation:remoteTarget,arguments:{reviewId:cloneReview.reviewId}}};mode='clone-confirm';
    }else if(action==='gitCloneConfirm'&&pending&&cloneReview){
     const asyncRemote=pending.operation==='startRemote';
     try{
      const result=await api.perform() as unknown as {operationId?:string};
      if(asyncRemote){if(!result.operationId)throw Error('Missing operation ID');operationId=result.operationId;remoteProgress=rt[6];mode='remote-running'}
      else{mode='clones';await loadClones();message=t[24];notice=true}
     }catch(error){mode='clones';await loadClones();throw error}
     finally{pending=null;cloneReview=null}
    }else if(action==='gitChanges'||action==='gitRefresh'){mode='changes';review=null;chunk=null;pending=null;cloneReview=null;await loadClones();await refresh()}
    else if(action==='gitPrevious'||action==='gitNext'){offset=Math.max(0,offset+(action==='gitNext'?10:-10));await refresh();mode='changes'}
    else if(action==='gitDiffWorking'||action==='gitDiffStaged'){
     const entry=status?.entries[Number(event.nodeId.split('_').pop())];if(!entry)throw Error('Changes list changed');
     review=await api.query('diff',{path:entry.path,staged:action==='gitDiffStaged'}) as unknown as Review;
     await read(0);mode='diff';
    }else if(action==='gitReadNext'&&review&&chunk&&chunk.nextOffset<review.total)await read(chunk.nextOffset);
    else if(action==='gitStage'&&review){pending={kind:'git.perform',operation:review.staged?'unstage':'stage',arguments:{reviewId:review.reviewId}};mode='confirm'}
    else if(action==='gitInitialize'){pending={kind:'git.perform',operation:'initialize',arguments:{}};mode='confirm'}
    else if(action==='gitCommit'){mode='commit';pending=null}
    else if(action==='gitRemote'){mode='remote';pending=null}
    else if(action==='gitCredentials'){
     remoteUrl=(event.fields.git_remote_url??remoteUrl).trim();
     if(!remoteUrl)throw Error(rt[1]);mode='credentials';
    }else if(action==='gitCredentialsConfirm'){
     const outcome=await api.credentials();mode='remote';message=outcome==='CANCELLED'?'':t[24];
    }
    else if(action==='gitClone'||action==='gitFetch'||action==='gitPull'||action==='gitPush'){
     remoteUrl=(event.fields.git_remote_url??remoteUrl).trim();remoteBranch=(event.fields.git_remote_branch??remoteBranch).trim();
     if(!remoteUrl||!remoteBranch)throw Error(rt[1]+' / '+rt[2]);
     remoteTarget=action==='gitClone'?'clone':action==='gitFetch'?'fetch':action==='gitPull'?'reviewPull':'reviewPush';
     pending={kind:'git.perform',operation:'startRemote',arguments:{operation:remoteTarget,arguments:{url:remoteUrl,branch:remoteBranch}}};mode='confirm';
    }else if(action==='gitRemoteApply'&&remoteReview){
     remoteTarget=remoteTarget==='reviewPush'?'push':'merge';
     pending={kind:'git.perform',operation:'startRemote',arguments:{operation:remoteTarget,arguments:{reviewId:remoteReview.reviewId}}};mode='confirm';
    }else if(action==='gitRemotePoll'&&operationId){
     const state=await api.query('remoteOperation',{operationId}) as unknown as {status:string;result?:{reviewId?:string;localHead?:string;remoteHead?:string;status?:string};error?:string;progress?:{task:string;completed:number;total:number}};
     remoteProgress=state.progress?`${state.progress.task} · ${state.progress.completed}${state.progress.total>0?'/'+state.progress.total:''}`:rt[6];
     if(state.status==='SUCCEEDED'){
      operationId='';
      if(remoteTarget==='reviewPush'||remoteTarget==='reviewPull'){
       if(!state.result?.reviewId||!state.result.localHead||!state.result.remoteHead)throw Error('Incomplete remote review');
       remoteReview=state.result as {reviewId:string;localHead:string;remoteHead:string};mode='remote-review';
      }else {message=state.result?.status??t[24];notice=true;mode='changes';await loadClones();await refresh()}
     }else if(state.status==='FAILED'||state.status==='CANCELLED'){
      operationId='';message=state.error??state.status;
      if(['clone','resumeCloneInstall','restartCloneDownload'].includes(remoteTarget)){mode='clones';await loadClones()}else mode='remote';
     }
    }else if(action==='gitRemoteCancel'){await api.perform();message=rt[8]}
    else if(action==='gitReviewCommit'){
     commitMessage=(event.fields.git_message??'').trim();author=(event.fields.git_author??'').trim();email=(event.fields.git_email??'').trim();
     if(!commitMessage||commitMessage.length>4096||!author||author.length>80||!email||email.length>254)throw Error('Message, author and email are required');
     const value=await api.query('reviewCommit',{}) as unknown as {revisionId:string};
     pending={kind:'git.perform',operation:'commit',arguments:{revisionId:value.revisionId,message:commitMessage,author,email}};mode='confirm';
    }else if(action==='gitConfirm'&&pending){
     const asyncRemote=pending.operation==='startRemote',result=await api.perform() as unknown as {operationId?:string};
     pending=null;review=null;chunk=null;
     if(asyncRemote){if(!result.operationId)throw Error('Missing operation ID');operationId=result.operationId;remoteProgress=rt[6];mode='remote-running'}
     else{mode='changes';await refresh();message=t[24];notice=true}
    }
    else if(action==='gitHistory'){commits=await api.query('history',{limit:5}) as unknown as Commit[];mode='history'}
    else if(action==='gitBack'){pending=null;mode='changes';await refresh()}
   }else {await loadClones();await refresh()}
  }catch(error){notice=false;message=t[23]+(error instanceof Error?error.message:String(error));}
  const nodes:PageNode[]=[],footer:Button[]=[],text=(id:string,value:string):PageNode=>({id,kind:'text',text:value}),
   button=(id:string,value:string,action=id):Button=>({id,kind:'button',text:value,action});
  if(message)nodes.push(text(notice?'git_notice':'git_error',Array.from(message).slice(0,1000).join('')));
  if(mode==='changes'){
   nodes.push(button('git_clones',cr[1]+(clones?.entries.length?' · '+clones.entries.length:''),'gitClones'));
   if(cloneError)nodes.push(text('git_clone_inventory_error',cloneError));
   nodes.push(text('git_branch',`${status?.branch??'—'} · ${status?.head?.slice(0,8)??'—'}`));
   nodes.push(button('git_remote',rt[0],'gitRemote'));
   for(const [i,entry]of (status?.entries??[]).entries())nodes.push({id:`git_file_${i}`,kind:'card',children:[text(`git_path_${i}`,entry.path),
    text(`git_state_${i}`,[entry.staged?t[7]:null,entry.working?t[6]:null,entry.conflict?t[8]:null].filter(Boolean).join(' · ')),
    ...(entry.working||entry.conflict?[button(`git_working_${i}`,t[9]+' · '+t[6],'gitDiffWorking')]:[]),
    ...(entry.staged?[button(`git_staged_${i}`,t[9]+' · '+t[7],'gitDiffStaged')]:[])]});
   if(status?.total===0)nodes.push(text('git_empty',t[21]));
   if(offset>0)nodes.push(button('git_previous',t[19],'gitPrevious'));
   if(status&&offset+status.entries.length<status.total)nodes.push(button('git_next',t[20],'gitNext'));
   footer.push(button('git_files',t[2],'gitFiles'),button('git_history',t[3],'gitHistory'),button('git_refresh',t[4],'gitRefresh'),
    button('git_commit',t[12],'gitCommit'));
   if(!status&&!clones?.entries.length)nodes.push(button('git_initialize',t[5],'gitInitialize'));
  }else if(mode==='clones'){
   nodes.push(text('git_clone_help',cr[6]));
   if(cloneError)nodes.push(text('git_clone_inventory_error',cloneError));
   if(clones?.incomplete||clones?.unreadable)nodes.push(text('git_clone_warning',cr[9]));
   for(const [i,entry]of (clones?.entries??[]).entries())nodes.push({id:`git_clone_${i}`,kind:'card',children:[
    text(`git_clone_source_${i}`,entry.url+' · '+entry.branch),text(`git_clone_fact_${i}`,entry.status+' · '+entry.phase+(entry.head?' · '+entry.head:'')),
    button(`git_clone_review_${i}`,cr[2],'gitCloneReview')]});
   if(clones&&!clones.entries.length)nodes.push(text('git_clone_empty',cr[7]));
   if(cloneOffset>0)nodes.push(button('git_clone_previous',t[19],'gitClonePrevious'));
   if(clones?.nextOffset!=null)nodes.push(button('git_clone_next',t[20],'gitCloneNext'));
   footer.push(button('git_back',t[18],'gitBack'),button('git_clone_refresh',t[4],'gitClones'),button('git_remote',rt[0],'gitRemote'));
  }else if((mode==='clone-review'||mode==='clone-confirm')&&cloneReview){
   nodes.push(text('git_clone_source',cr[10]+' · '+cloneReview.entry.url+' · '+cloneReview.entry.branch),
    text('git_clone_fact',cloneReview.entry.status+' · '+cloneReview.entry.phase),
    text('git_clone_head',cloneReview.entry.head??'—'),
    text('git_clone_counts',cr[8]+' · '+cloneReview.files+' / '+cloneReview.bytes+' / '+cloneReview.installedFiles),text('git_clone_help',cr[11]));
   if(cloneReview.problem)nodes.push(text('git_clone_problem',cloneReview.problem));
   footer.push(button('git_clone_back',t[18],'gitClones'));
   if(mode==='clone-confirm'&&pending){
    const label=remoteTarget==='resumeCloneInstall'?cr[3]:remoteTarget==='restartCloneDownload'?cr[4]:cr[5];
    footer.push({...button('git_clone_confirm',label,'gitCloneConfirm'),hostAction:pending});
   }else{
    if(cloneReview.canResume)footer.push(button('git_clone_resume',cr[3],'gitCloneResume'));
    if(cloneReview.canRestart)footer.push(button('git_clone_restart',cr[4],'gitCloneRestart'));
    footer.push(button('git_clone_discard',cr[5],'gitCloneDiscard'));
   }
  }else if(mode==='remote'){
   nodes.push({id:'git_remote_url',kind:'field',label:rt[1],value:remoteUrl},
    {id:'git_remote_branch',kind:'field',label:rt[2],value:remoteBranch},text('git_remote_help',rt[13]),
    button('git_clone',cloneLabel,'gitClone'),button('git_fetch',rt[3],'gitFetch'),button('git_pull',rt[4],'gitPull'),button('git_push',rt[5],'gitPush'),
    button('git_credentials',credentialLabel,'gitCredentials'));
   footer.push(button('git_back',t[18],'gitBack'));
  }else if(mode==='credentials'){
   nodes.push(text('git_credential_repository',remoteUrl));
   footer.push(button('git_back',t[18],'gitBack'),{...button('git_credentials_confirm',credentialLabel,'gitCredentialsConfirm'),hostAction:{kind:'git.credentials',url:remoteUrl}});
  }else if(mode==='remote-running'){
   nodes.push(text('git_transfer',remoteProgress));
   footer.push(button('git_remote_poll',rt[7],'gitRemotePoll'),
    {...button('git_remote_cancel',rt[8],'gitRemoteCancel'),hostAction:{kind:'git.perform',operation:'cancelTransfer',arguments:{}}});
  }else if(mode==='remote-review'&&remoteReview){
   nodes.push(text('git_remote_address',remoteUrl+' · '+remoteBranch),text('git_local_head',rt[11]+' · '+remoteReview.localHead),
    text('git_remote_head',rt[12]+' · '+remoteReview.remoteHead),text('git_remote_help',rt[13]));
   footer.push(button('git_back',t[18],'gitBack'),button('git_remote_apply',remoteTarget==='reviewPush'?rt[10]:rt[9],'gitRemoteApply'));
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
