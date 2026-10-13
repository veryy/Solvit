/* Solvit page logic. xterm.js and addon-fit are pinned MIT distributions bundled offline. */
(() => {
  const $ = id => document.getElementById(id);
  let token = null, initialized = false, shells = [], selected = null, sequence = 0, closed = false, pendingCount = 0;
  let theme = {}, labels = [], availableTerminals = [];
  const sessions = new Map(), pending = new Map();
  const translations = {
    zh: ['交互终端','同一终端保留目录和环境。终端在本应用身份下运行。','打开终端','命令或交互输入','发送','关闭','Shell 名称','取消','创建','运行中','已关闭','部分旧输出已回收','输入过快，请稍后重试'],
    en: ['Terminal','Directory and environment persist in this terminal. Runs as this app.','Open terminal','Command or interactive answer','Send','Close','Shell name','Cancel','Create','Running','Closed','Older output was discarded','Input queue full; retry'],
    ja: ['ターミナル','同じ端末でディレクトリと環境を保持します。','端末を開く','コマンドまたは対話入力','送信','閉じる','Shell名','キャンセル','作成','実行中','終了','古い出力は破棄されました','入力待ちが多すぎます'],
    ko: ['터미널','같은 터미널에서 디렉터리와 환경을 유지합니다.','터미널 열기','명령 또는 응답','보내기','닫기','Shell 이름','취소','만들기','실행 중','닫힘','이전 출력이 삭제됨','입력 대기열이 가득 참'],
    es: ['Terminal','El directorio y entorno se conservan. Se ejecuta como esta app.','Abrir terminal','Comando o respuesta','Enviar','Cerrar','Nombre de Shell','Cancelar','Crear','En ejecución','Cerrado','Se descartó la salida antigua','Cola de entrada llena'],
    fr: ['Terminal','Le répertoire et l’environnement sont conservés. Exécution sous cette application.','Ouvrir le terminal','Commande ou réponse','Envoyer','Fermer','Nom du Shell','Annuler','Créer','En cours','Fermé','Ancienne sortie supprimée','File de saisie pleine']
  };
  function report(error) { $('status').textContent = error instanceof Error ? error.message : String(error); }
  function rpc(operation, args = {}) {
    if (closed || !token) return Promise.reject(Error('Page closed'));
    if (pendingCount >= 64) return Promise.reject(Error(labels[12]));
    const id = `t${++sequence}`;
    return new Promise((resolve, reject) => {
      // Opening may wait for a human; don't expire an unanswered permission prompt.
      const timer = setTimeout(() => { if (pending.delete(id)) { pendingCount--; reject(Error('Terminal request timed out')); } }, operation === 'open' || operation === 'createShell' || operation === 'attach' ? 120000 : 15000);
      pending.set(id, {resolve, reject, timer}); pendingCount++;
      try { window.solvitHost.postMessage(JSON.stringify({type:'terminal',token,id,operation,arguments:args})); }
      catch (error) { clearTimeout(timer);pending.delete(id);pendingCount--;reject(error); }
    });
  }
  window.solvitHost.onmessage = message => {
    const data = JSON.parse(message.data);
    if (data.type !== 'terminalReply') return;
    const request = pending.get(data.id); if (!request) return;
    pending.delete(data.id);pendingCount--;clearTimeout(request.timer);
    data.error ? request.reject(Error(data.error)) : request.resolve(data.result);
  };
  function fit(entry) {
    const dimensions = entry.fit.proposeDimensions();
    if (!dimensions || entry.container.hidden) return;
    const rows = Math.max(2,Math.min(300,dimensions.rows)), cols = Math.max(2,Math.min(500,dimensions.cols));
    if (entry.term.rows !== rows || entry.term.cols !== cols) entry.term.resize(cols,rows);
    if (entry.active && (entry.rows !== rows || entry.columns !== cols)) {
      entry.rows=rows;entry.columns=cols;
      rpc('resize',{id:entry.id,rows,columns:cols}).catch(report);
    }
  }
  function update(entry, result) {
    if (result.id !== entry.id || result.revision < entry.revision) return;
    if (result.revision !== undefined) entry.revision = result.revision;
    const start = result.outputOffset, end = start + result.output.length;
    if (entry.position < start || entry.position > end) { entry.term.reset();entry.position=start;report(labels[11]); }
    const text = result.output.slice(entry.position-start);
    if (text) entry.term.write(text);
    entry.position=end;entry.active=result.active;
    entry.term.options.disableStdin=!entry.active;
    if (sessions.get(selected) === entry) {
      $('status').textContent = result.failure || (entry.active ? `${labels[9]} · ${entry.rows} × ${entry.columns}` : `${labels[10]} · ${result.exitCode ?? '—'}`);
      $('command').disabled=!entry.active;$('send').disabled=!entry.active;$('close').disabled=!entry.active;
    }
  }
  function choose(id) {
    selected=id;
    for (const [shellId,entry] of sessions) entry.container.hidden=shellId!==selected;
    for (const button of $('shells').children) button.classList.toggle('selected',button.dataset.id===selected);
    const entry=sessions.get(id);$('welcome').hidden=!!entry;$('terminals').hidden=!entry;
    if (!entry) $('status').textContent='';
    $('command').disabled=!entry?.active;$('send').disabled=!entry?.active;$('close').disabled=!entry?.active;
    if (entry) { fit(entry);update(entry,{id:entry.id,outputOffset:entry.position,output:'',active:entry.active,exitCode:null}); }
  }
  function terminalChoices(state) {
    availableTerminals=state.terminals;
    const previous=$('running').value;
    $('running').replaceChildren(...availableTerminals.map(session=>{
      const option=document.createElement('option');option.value=session.id;
      option.textContent=`${shells.find(s=>s.id===session.shellId)?.name??'Shell'} · ${session.owner}${session.roleId?' · '+session.roleId:''}`;return option;
    }));
    if(availableTerminals.some(s=>s.id===previous))$('running').value=previous;
    $('sessions').hidden=!availableTerminals.length;
  }
  async function refreshShells() {
    const state=await rpc('snapshot');shells=state.shells;
    $('shells').replaceChildren(...shells.map(shell => {
      const button=document.createElement('button');button.textContent=shell.name;button.dataset.id=shell.id;button.onclick=()=>choose(shell.id);return button;
    }));
    terminalChoices(state);
    choose(shells.some(s=>s.id===selected)?selected:shells[0]?.id ?? null);$('open').disabled=!selected;
  }
  async function open() {
    if (!selected) return;
    const shellId=selected;$('open').disabled=true;
    try {
      const result=await rpc('open',{shellId});
      acceptSession(shellId,result);
    } catch(error){report(error)} finally{$('open').disabled=!selected}
  }
  function acceptSession(shellId,result) {
      let entry=sessions.get(shellId);
      if (!entry || entry.id!==result.id) {
        if (entry) { entry.term.dispose();entry.container.remove(); }
        const container=document.createElement('div');container.className='terminal-container';$('terminals').append(container);
        const term=new window.Terminal({fontSize:13,fontFamily:'monospace',cursorBlink:true,scrollback:2000,theme:{background:theme.surface,foreground:theme.onSurface,cursor:theme.primary},allowProposedApi:false});
        const fitAddon=new window.FitAddon.FitAddon();term.loadAddon(fitAddon);term.open(container);
        entry={id:result.id,term,fit:fitAddon,container,active:result.active,position:result.outputOffset,rows:result.rows,columns:result.columns,revision:result.revision};sessions.set(shellId,entry);
        term.onData(text=>{ if(entry.active) rpc('input',{id:entry.id,text}).then(result=>update(entry,result)).catch(report); });
      }
      update(entry,result);choose(shellId);fit(entry);
  }
  $('open').onclick=open;
  $('refresh').onclick=()=>refreshShells().catch(report);
  $('attach').onclick=async()=>{const candidate=availableTerminals.find(s=>s.id===$('running').value);if(!candidate)return;try{acceptSession(candidate.shellId,await rpc('attach',{id:candidate.id}))}catch(error){report(error)}};
  $('keys').addEventListener('click',event=>{
    const key=event.target.dataset.key,entry=sessions.get(selected);
    if(!key || !entry?.active)return;
    const text=key.replace(/\\u([0-9a-f]{4})/gi,(_,hex)=>String.fromCharCode(parseInt(hex,16)));
    rpc(text==='\x03'?'interrupt':'input',text==='\x03'?{id:entry.id}:{id:entry.id,text}).then(result=>update(entry,result)).catch(report);
  });
  $('close').onclick=async()=>{const entry=sessions.get(selected);if(!entry)return;try{await rpc('close',{id:entry.id});entry.active=false;entry.term.options.disableStdin=true;choose(selected);$('welcome').hidden=false;}catch(error){report(error)}};
  $('input-form').onsubmit=event=>{event.preventDefault();const entry=sessions.get(selected),text=$('command').value;if(!entry?.active)return;
    // Preserve the typed command on failure, rather than silently discarding it.
    $('send').disabled=true;rpc('input',{id:entry.id,text:text+'\n'}).then(result=>{if($('command').value===text)$('command').value='';update(entry,result)}).catch(report).finally(()=>{$('send').disabled=!sessions.get(selected)?.active});};
  $('add').onclick=()=>{$('shell-name').value=`Shell ${shells.length+1}`;$('new-shell').showModal()};
  $('new-shell').addEventListener('close',async()=>{if($('new-shell').returnValue!=='create')return;try{await rpc('createShell',{name:$('shell-name').value.trim()});await refreshShells();choose(shells.at(-1)?.id)}catch(error){report(error)}});
  new ResizeObserver(()=>{const entry=sessions.get(selected);if(entry)fit(entry)}).observe($('terminals'));
  async function poll(){while(!closed){const entry=sessions.get(selected);if(entry?.active && pendingCount===0){try{update(entry,await rpc('status',{id:entry.id}))}catch(error){entry.active=false;entry.term.options.disableStdin=true;choose(selected);report(error)}}await new Promise(resolve=>setTimeout(resolve,200))}}
  window.solvitPageUpdate=state=>{
    token=state.token;theme=state.theme;labels=translations[state.locale.split(/[-_]/)[0]]??translations.en;
    for(const [key,value] of Object.entries(theme))document.documentElement.style.setProperty('--'+key,value);
    for(const [id,index] of [['title',0],['intro',1],['open',2],['send',4],['close',5],['name-label',6],['cancel',7],['create',8]])$(id).textContent=labels[index];
    $('attach').textContent=state.locale.startsWith('zh')?'连接已有终端':'Connect session';
    $('command').placeholder=labels[3];$('command').setAttribute('aria-label',labels[3]);
    for(const entry of sessions.values())entry.term.options.theme={background:theme.surface,foreground:theme.onSurface,cursor:theme.primary};
    if(!initialized){initialized=true;refreshShells().catch(report);poll()}
  };
  window.addEventListener('pagehide',()=>{closed=true;for(const request of pending.values()){clearTimeout(request.timer);request.reject(Error('Page closed'))}pending.clear();for(const entry of sessions.values())entry.term.dispose()});
  window.solvitHost.postMessage(JSON.stringify({type:'ready'}));
})();
