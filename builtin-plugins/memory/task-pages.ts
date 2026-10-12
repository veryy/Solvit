import type { PluginPageInput, PluginPageView, PluginContext, PluginTask, PageNode, PageHostAction } from '../../plugin-sdk/src/index';

const en = {
  title:'Background memory tasks', empty:'No tasks on this page', unavailable:'Task management is unavailable on this host.',
  refresh:'Refresh', back:'Back to memories', previous:'Previous', next:'Next', first:'First page', details:'Details',
  list:'Back to task list', retry:'Retry', remove:'Delete task', stop:'Stop', cleanup:'Clean completed tasks', attempts:'Attempts', role:'Role',
  model:'Model', session:'Session ID', user:'Source user message', assistant:'Source assistant message', id:'Task ID',
  changed:'The task changed. Refresh before trying again.', failed:'Unable to complete this action. Refresh and check its state.',
  cost:'Retries use the original model configuration and may incur charges.', unassigned:'Unassigned role',
  status:['Waiting','Running','Completed','Failed','Skipped'],
  failures:['Interrupted','Deadline exceeded','Input budget exceeded','Model request failed','Invalid extracted facts',
    'Storage failed or full','Permission revoked','Plugin changed','Original model configuration unavailable'],
};
const words:Record<string,typeof en>={en,
  zh:{title:'自动记忆任务',empty:'本页暂无任务',unavailable:'当前宿主无法提供任务管理。',refresh:'刷新',back:'返回记忆',previous:'上一页',next:'下一页',first:'第一页',details:'查看详情',list:'返回任务列表',retry:'重试',remove:'删除任务',stop:'停止',cleanup:'清理完成任务',attempts:'尝试次数',role:'角色',model:'模型',session:'会话 ID',user:'来源用户消息',assistant:'来源助手消息',id:'任务 ID',changed:'任务状态已变化，请刷新后再操作。',failed:'操作未完成，请刷新核对任务状态。',cost:'重试使用原模型配置，可能产生额外费用。',unassigned:'未指定角色',status:['等待处理','正在处理','处理完成','处理失败','已跳过'],failures:['处理已中断','处理超时','输入超过预算','模型请求失败','记忆格式无效','存储失败或已满','授权已撤销','插件已变化','原模型配置不可用']},
  ja:{title:'記憶のバックグラウンドタスク',empty:'このページにタスクはありません',unavailable:'このホストではタスク管理を利用できません。',refresh:'更新',back:'記憶に戻る',previous:'前のページ',next:'次のページ',first:'最初のページ',details:'詳細',list:'タスク一覧に戻る',retry:'再試行',remove:'タスクを削除',stop:'停止',cleanup:'完了したタスクを整理',attempts:'試行回数',role:'ロール',model:'モデル',session:'セッション ID',user:'元のユーザーメッセージ',assistant:'元のアシスタントメッセージ',id:'タスク ID',changed:'状態が変わりました。更新してから操作してください。',failed:'操作を完了できません。更新して状態を確認してください。',cost:'再試行は元のモデル設定を使い、追加料金が発生する場合があります。',unassigned:'ロール未指定',status:['待機中','実行中','完了','失敗','スキップ'],failures:['中断','タイムアウト','入力予算超過','モデルリクエスト失敗','記憶の形式が無効','ストレージ失敗または容量不足','権限取り消し','プラグイン変更','元のモデル設定が利用不可']},
  ko:{title:'기억 백그라운드 작업',empty:'이 페이지에 작업이 없습니다',unavailable:'이 호스트에서 작업 관리를 사용할 수 없습니다.',refresh:'새로고침',back:'기억으로 돌아가기',previous:'이전',next:'다음',first:'첫 페이지',details:'상세',list:'작업 목록으로 돌아가기',retry:'재시도',remove:'작업 삭제',stop:'중지',cleanup:'완료 작업 정리',attempts:'시도 횟수',role:'역할',model:'모델',session:'세션 ID',user:'원본 사용자 메시지',assistant:'원본 도우미 메시지',id:'작업 ID',changed:'상태가 변경되었습니다. 새로고침 후 다시 시도하세요.',failed:'작업을 완료하지 못했습니다. 새로고침하여 상태를 확인하세요.',cost:'재시도는 원래 모델 설정을 사용하며 추가 비용이 발생할 수 있습니다.',unassigned:'역할 미지정',status:['대기','실행 중','완료','실패','건너뜀'],failures:['중단','시간 초과','입력 예산 초과','모델 요청 실패','기억 형식 오류','저장 실패 또는 용량 부족','권한 취소','플러그인 변경','원래 모델 설정을 사용할 수 없음']},
  es:{title:'Tareas de memoria en segundo plano',empty:'No hay tareas en esta página',unavailable:'La gestión de tareas no está disponible.',refresh:'Actualizar',back:'Volver a los recuerdos',previous:'Anterior',next:'Siguiente',first:'Primera página',details:'Detalles',list:'Volver a la lista de tareas',retry:'Reintentar',remove:'Eliminar tarea',stop:'Detener',cleanup:'Limpiar tareas completadas',attempts:'Intentos',role:'Rol',model:'Modelo',session:'ID de sesión',user:'Mensaje original del usuario',assistant:'Mensaje original del asistente',id:'ID de tarea',changed:'La tarea cambió. Actualiza antes de reintentar.',failed:'No se pudo completar la acción. Actualiza y comprueba el estado.',cost:'Los reintentos usan la configuración original del modelo y pueden generar cargos.',unassigned:'Sin rol',status:['En espera','En ejecución','Completada','Fallida','Omitida'],failures:['Interrumpida','Tiempo agotado','Presupuesto de entrada superado','Solicitud al modelo fallida','Recuerdos extraídos inválidos','Almacenamiento fallido o lleno','Permiso revocado','Complemento modificado','Configuración original no disponible']},
  fr:{title:'Tâches de mémoire en arrière-plan',empty:'Aucune tâche sur cette page',unavailable:'La gestion des tâches est indisponible.',refresh:'Actualiser',back:'Retour aux souvenirs',previous:'Précédent',next:'Suivant',first:'Première page',details:'Détails',list:'Retour à la liste des tâches',retry:'Réessayer',remove:'Supprimer la tâche',stop:'Arrêter',cleanup:'Nettoyer les tâches terminées',attempts:'Tentatives',role:'Rôle',model:'Modèle',session:'ID de session',user:'Message utilisateur source',assistant:'Message assistant source',id:'ID de tâche',changed:'La tâche a changé. Actualisez avant de réessayer.',failed:'Action impossible. Actualisez pour vérifier son état.',cost:'Les nouvelles tentatives utilisent le modèle initial et peuvent entraîner des frais.',unassigned:'Rôle non attribué',status:['En attente','En cours','Terminée','Échec','Ignorée'],failures:['Interrompue','Délai dépassé','Budget d’entrée dépassé','Requête au modèle échouée','Souvenirs extraits invalides','Stockage en échec ou plein','Autorisation révoquée','Plugin modifié','Configuration initiale indisponible']},
};
const statuses=['WAITING','RUNNING','SUCCEEDED','FAILED','SKIPPED'];
const failures=['INTERRUPTED','DEADLINE','INPUT_TOO_LARGE','MODEL','INVALID_OUTPUT','STORAGE','PERMISSION','PLUGIN_CHANGED','CONFIGURATION'];
type Button=Extract<PageNode,{kind:'button'}>;
const text=(id:string,value:string):PageNode=>({id,kind:'text',text:value});
const button=(id:string,label:string,action=id,hostAction?:PageHostAction):Button=>({id,kind:'button',text:label,action,...(hostAction?{hostAction}:{})});

/** Navigation stays in this page engine; the host owns task state and confirmed mutations. */
export function createMemoryTasksPage() {
  let selected:string|null=null, displayed:PluginTask[]=[];
  let cursors:({updatedAt:number;id:string}|null)[]=[null];
  let next:{updatedAt:number;id:string}|null=null;
  return async(input:PluginPageInput,ctx:PluginContext):Promise<PluginPageView>=>{
    const t=words[input.locale.split('-')[0]]??words.zh;
    if(!ctx.jobs)return {title:t.title,nodes:[text('unavailable',t.unavailable)],footer:[button('back',t.back)]};
    let message='';
    try {
      const action=input.event?.action;
      if(action?.startsWith('task_open_'))selected=displayed[Number(action.slice(10))]?.id??null;
      else if(action==='task_back')selected=null;
      else if(action==='task_first'){cursors=[null];selected=null;}
      else if(action==='task_next'&&next){cursors.push(next);selected=null;}
      else if(action==='task_previous'&&cursors.length>1){cursors.pop();selected=null;}
      else if(action==='task_control') {
        // No operation, job ID or payload arguments can override native confirmation.
        const result=await ctx.jobs.perform();
        if(result.changed===0&&result.receipts===0)message=t.changed;
        cursors=[null];
      }
    } catch {message=t.failed;}
    const listing=await ctx.jobs.list({cursor:cursors[cursors.length-1]});
    if(!listing.available)return {title:t.title,nodes:[text('unavailable',t.unavailable)],footer:[button('back',t.back)]};
    displayed=listing.jobs;next=listing.nextCursor;
    const nodes:PageNode[]=[text('task_cost',t.cost)];
    if(message)nodes.push(text('task_message',message));
    const status=(job:PluginTask)=>t.status[statuses.indexOf(job.status)]??job.status;
    const failure=(job:PluginTask)=>job.failure?t.failures[failures.indexOf(job.failure)]??job.failure:'';
    const job=selected?displayed.find(row=>row.id===selected):null;
    if(selected&&!job)selected=null;
    if(job) {
      nodes.push(text('task_status',status(job)),text('task_id',`${t.id}: ${job.id}`),
        text('task_attempts',`${t.attempts}: ${job.attempt}/3`),text('task_role',`${t.role}: ${job.roleName??t.unassigned}`),
        text('task_model',`${t.model}: ${job.model}`),text('task_session',`${t.session}: ${job.sessionId}`),
        text('task_user',`${t.user}: ${job.userMessageId}`),text('task_assistant',`${t.assistant}: ${job.assistantMessageId}`));
      if(job.failure)nodes.push(text('task_failure',failure(job)));
      const footer:Button[]=[button('task_back',t.list),button('task_refresh',t.refresh)];
      if(job.status==='FAILED'&&job.attempt<3&&job.attempt>0)footer.push(button('task_retry',t.retry,'task_control',{kind:'postTurn.retry',jobId:job.id,attempt:job.attempt}));
      if(job.status!=='RUNNING')footer.push(button('task_delete',t.remove,'task_control',{kind:'postTurn.delete',jobId:job.id,attempt:job.attempt,status:job.status}));
      if(listing.activeJobId===job.id)footer.push(button('task_stop',t.stop,'task_control',{kind:'postTurn.stop',jobId:job.id,attempt:job.attempt}));
      return {title:t.title,nodes,footer};
    }
    displayed.forEach((row,i)=>nodes.push({id:`task_${i}`,kind:'card',children:[text(`task_status_${i}`,status(row)),
      text(`task_role_${i}`,row.roleName??t.unassigned),...(row.failure?[text(`task_failure_${i}`,failure(row))]:[]),button(`task_open_${i}`,t.details)]}));
    if(!displayed.length)nodes.push(text('task_empty',t.empty));
    nodes.push({id:'task_pagination',kind:'row',children:[{...button('task_previous',t.previous),enabled:cursors.length>1},
      {...button('task_next',t.next),enabled:next!==null},button('task_first',t.first)]});
    return {title:t.title,nodes,footer:[button('back',t.back),button('task_refresh',t.refresh),
      button('task_cleanup',t.cleanup,'task_control',{kind:'postTurn.cleanup'})]};
  };
}
