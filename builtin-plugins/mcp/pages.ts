import type {PageNode,PluginPageInput,PluginPageView,PluginContext} from '../../plugin-sdk/src/index';
import {readConnections,validateConnections,importConnections,isLocal,type Connection} from './config';
const words={
  zh:['MCP 服务器','添加连接','导入配置','名称','HTTPS 地址','Bearer 令牌（留空保留）','请求头 JSON（留空保留）','保存','返回','编辑','测试连接','停用','启用','删除','确定删除此连接？','保存连接配置？','清除认证','清除该连接的令牌和请求头？','已启用','已停用','配置已变化，请返回重新编辑。','操作失败，请检查名称、HTTPS 地址和配置格式。','连接测试成功','连接测试失败；请检查地址、认证和服务器是否可用。','粘贴 mcpServers JSON','导入将替换所有连接，确定继续？','暂无服务器，请添加或导入。'],
  en:['MCP servers','Add server','Import config','Name','HTTPS endpoint','Bearer token (blank keeps existing)','Headers JSON (blank keeps existing)','Save','Back','Edit','Test connection','Disable','Enable','Delete','Delete this connection?','Save connections?','Clear authentication','Clear this server’s token and headers?','Enabled','Disabled','Configuration changed. Go back and edit again.','Action failed. Check name, HTTPS endpoint and configuration format.','Connection verified','Connection failed. Check endpoint, authentication and server availability.','Paste mcpServers JSON','Import replaces all connections. Continue?','No servers yet. Add or import one.'],
  ja:['MCPサーバー','接続を追加','設定をインポート','名前','HTTPSアドレス','Bearerトークン（空欄は維持）','ヘッダーJSON（空欄は維持）','保存','戻る','編集','接続テスト','無効化','有効化','削除','この接続を削除しますか？','接続設定を保存しますか？','認証をクリア','トークンとヘッダーを削除しますか？','有効','無効','設定が変更されました。戻って再編集してください。','名前、HTTPSアドレスと設定形式を確認してください。','接続確認済み','接続に失敗しました。アドレスと認証を確認してください。','mcpServers JSONを貼り付け','すべての接続を置き換えますか？','サーバーがありません。追加してください。'],
  ko:['MCP 서버','연결 추가','설정 가져오기','이름','HTTPS 주소','Bearer 토큰 (비우면 유지)','헤더 JSON (비우면 유지)','저장','뒤로','편집','연결 테스트','비활성화','활성화','삭제','연결을 삭제할까요?','연결 설정을 저장할까요?','인증 지우기','토큰과 헤더를 지울까요?','활성화됨','비활성화됨','설정이 변경됐습니다. 다시 편집하세요.','이름, HTTPS 주소와 설정 형식을 확인하세요.','연결 확인됨','연결 실패. 주소와 인증을 확인하세요.','mcpServers JSON 붙여넣기','모든 연결을 교체할까요?','서버가 없습니다. 추가하세요.'],
  es:['Servidores MCP','Añadir conexión','Importar configuración','Nombre','Dirección HTTPS','Token Bearer (vacío conserva)','Cabeceras JSON (vacío conserva)','Guardar','Volver','Editar','Probar conexión','Desactivar','Activar','Eliminar','¿Eliminar esta conexión?','¿Guardar conexiones?','Borrar autenticación','¿Borrar token y cabeceras?','Activo','Inactivo','La configuración cambió. Vuelve a editar.','Revisa nombre, dirección HTTPS y formato.','Conexión verificada','Conexión fallida. Revisa dirección y autenticación.','Pegar JSON mcpServers','¿Reemplazar todas las conexiones?','Sin servidores. Añade o importa uno.'],
  fr:['Serveurs MCP','Ajouter une connexion','Importer','Nom','Adresse HTTPS','Jeton Bearer (vide pour conserver)','En-têtes JSON (vide pour conserver)','Enregistrer','Retour','Modifier','Tester','Désactiver','Activer','Supprimer','Supprimer cette connexion ?','Enregistrer les connexions ?','Effacer l’authentification','Effacer le jeton et les en-têtes ?','Actif','Inactif','La configuration a changé. Revenez la modifier.','Vérifiez le nom, l’adresse HTTPS et le format.','Connexion vérifiée','Échec de connexion. Vérifiez l’adresse et l’authentification.','Coller le JSON mcpServers','Remplacer toutes les connexions ?','Aucun serveur. Ajoutez ou importez une connexion.'],
};
const localWords={
 zh:['本地 stdio','启动命令（如 python3 或 node）','参数 JSON 数组','环境变量 JSON（留空保留）','工作区内相对目录（留空为根目录）','请先在工作区安装 Ubuntu 和所需 Python/Node 软件，再导入或编写服务脚本。普通应用权限仍受 Android 限制。','从已绑定工作区的聊天打开此页面后可测试连接。','测试将启动保存的本地服务；仅协商协议和发现工具，完成后关闭。','参数数','环境变量数','清除该连接保存的认证或环境变量？'],
 en:['Local stdio','Command (e.g. python3 or node)','Arguments JSON array','Environment JSON (blank keeps existing)','Workspace-relative directory (blank for root)','Install Ubuntu and the required Python/Node software in Workspace, then add your server script. Android permissions still apply.','Open this page from a chat with a bound workspace to test.','Starts the saved local service, negotiates and lists tools, then closes it.','Arguments','Environment variables','Clear saved authentication or environment variables?'],
 ja:['ローカル stdio','コマンド（python3、node など）','引数 JSON 配列','環境変数 JSON（空欄は維持）','ワークスペース内の相対ディレクトリ','ワークスペースで Ubuntu と Python/Node をインストールし、サーバースクリプトを追加してください。Android の権限は引き続き適用されます。','ワークスペースに接続したチャットから開いてテストしてください。','保存したサービスを起動し、接続とツール一覧を確認して終了します。','引数の数','環境変数の数','認証または環境変数を削除しますか？'],
 ko:['로컬 stdio','명령 (예: python3, node)','인수 JSON 배열','환경 변수 JSON (비우면 유지)','작업 공간 상대 경로','작업 공간에서 Ubuntu와 Python/Node를 설치하고 서버 스크립트를 추가하세요. Android 권한 제한은 유지됩니다.','작업 공간이 연결된 채팅에서 열어 테스트하세요.','저장된 서비스를 시작하고 연결과 도구 목록을 확인한 뒤 닫습니다.','인수 수','환경 변수 수','인증 또는 환경 변수를 지울까요?'],
 es:['stdio local','Comando (python3 o node)','Argumentos: array JSON','Entorno JSON (vacío conserva)','Directorio relativo al espacio de trabajo','Instala Ubuntu y Python/Node en el espacio de trabajo y añade el script. Se mantienen los permisos de Android.','Abre desde un chat con espacio de trabajo para probar.','Inicia el servicio guardado, negocia, lista herramientas y lo cierra.','Argumentos','Variables de entorno','¿Borrar autenticación o variables de entorno?'],
 fr:['stdio local','Commande (python3 ou node)','Arguments : tableau JSON','Environnement JSON (vide pour conserver)','Répertoire relatif à l’espace de travail','Installez Ubuntu et Python/Node dans l’espace de travail, puis ajoutez le script. Les permissions Android restent applicables.','Ouvrez depuis un chat lié à un espace de travail pour tester.','Démarre le service enregistré, négocie, liste les outils, puis le ferme.','Arguments','Variables d’environnement','Effacer l’authentification ou les variables d’environnement ?'],
};
const text=(id:string,value:string):PageNode=>({id,kind:'text',text:value});
type Button=Extract<PageNode,{kind:'button'}>;
const button=(id:string,label:string,action=id,confirm?:string):Button=>({id,kind:'button',text:label,action,...(confirm?{confirm}:{})});
const field=(id:string,label:string,value:string,multiline=false):PageNode=>({id,kind:'field',label,value,multiline});
export function createMcpPage(probe:(ctx:PluginContext,name:string)=>Promise<{protocol:string;tools:number}>) {
  let mode:'list'|'edit'|'import'='list', selected:string|null=null;
  let expected:string|null=null, visible:Connection[]=[],draft:Record<string,string>={};
  const tested=new Map<string,string>();
  return async(input:PluginPageInput,ctx:PluginContext):Promise<PluginPageView>=>{
    const language=input.locale.split('-')[0] as keyof typeof words;
    const t=words[language]??words.zh,l=localWords[language]??localWords.zh;
    let config=await readConnections(ctx),message='';
    const event=input.event;
    const store=async(servers:Connection[])=>{
      const next=JSON.stringify(validateConnections(servers));
      if(!ctx.settings.compareAndSet||!await ctx.settings.compareAndSet('connections',expected,next))throw new Error('CONFLICT');
      config=await readConnections(ctx);expected=config.encoded;tested.clear();
    };
    try {
      const action=event?.action;
      if(mode==='edit'&&event)draft={...draft,...event.fields};
      if(action==='back'){mode='list';selected=null;draft={}}
      else if(action==='new'){expected=config.encoded;selected=null;draft={name:'',type:'http',url:'',token:'',headers:'',command:'',args:'[]',env:'',cwd:''};mode='edit'}
      else if(action==='import'){expected=config.encoded;draft={config:''};mode='import'}
      else if(action==='edit'){if(config.encoded!==expected)throw new Error('CONFLICT');const profile=visible[Number(event!.nodeId.slice(5))];if(!profile)throw new Error('INVALID');
        expected=config.encoded;selected=profile.name;draft=isLocal(profile)?{name:profile.name,type:'stdio',command:profile.command,args:JSON.stringify(profile.args),env:'',cwd:profile.cwd}:{name:profile.name,type:'http',url:profile.url,token:'',headers:''};mode='edit'}
      else if((action==='transport_http'||action==='transport_stdio')&&mode==='edit'){draft={...draft,type:action==='transport_stdio'?'stdio':'http'}}
      else if(action==='save'&&mode==='edit'){
        if(config.encoded!==expected)throw new Error('CONFLICT');
        const previous=config.servers.find(profile=>profile.name===selected);
        const candidate=draft.type==='stdio'?{name:draft.name?.trim(),type:'stdio',command:draft.command?.trim(),
          args:JSON.parse(draft.args||'[]'),env:draft.env?.trim()?JSON.parse(draft.env):previous&&isLocal(previous)?previous.env:{},cwd:draft.cwd?.trim()??'',disabled:previous?.disabled??false}:
          {name:draft.name?.trim(),url:draft.url?.trim(),token:draft.token||(previous&&!isLocal(previous)?previous.token:''),
          headers:draft.headers?.trim()?JSON.parse(draft.headers):previous&&!isLocal(previous)?previous.headers:{},disabled:previous?.disabled??false};
        const replacement=validateConnections([candidate])[0];
        const servers=selected ? config.servers.map(profile=>profile.name===selected?replacement:profile) : [...config.servers,replacement];
        await store(servers);mode='list';draft={};
      }else if(action==='import_save'&&mode==='import'){
        draft={config:event!.fields.config??''};await store(importConnections(draft.config));mode='list';draft={};
      }else if(action==='clear_auth'&&mode==='edit'){
        if(config.encoded!==expected)throw new Error('CONFLICT');
        await store(config.servers.map(profile=>profile.name===selected?(isLocal(profile)?{...profile,env:{}}:{...profile,token:'',headers:{}}):profile));mode='list';draft={};
      }else if(action==='toggle'||action==='delete'){
        const index=Number(event!.nodeId.slice(event!.nodeId.lastIndexOf('_')+1)),profile=visible[index];
        if(!profile||config.encoded!==expected)throw new Error('CONFLICT');
        await store(action==='delete'?config.servers.filter(p=>p.name!==profile.name):config.servers.map(p=>p.name===profile.name?{...p,disabled:!p.disabled}:p));
      }else if(action==='probe'){
        const profile=visible[Number(event!.nodeId.slice(6))];if(!profile||profile.disabled)throw new Error('INVALID');
        try{const result=await probe(ctx,profile.name);tested.set(profile.name,`${t[22]} · ${result.protocol} · ${result.tools}`)}
        catch{tested.set(profile.name,t[23])}
      }
    }catch(error){message=error instanceof Error&&error.message==='CONFLICT'?t[20]:t[21]}
    const nodes:PageNode[]=message?[text('message',message)]:[];
    if(mode==='edit'){
      nodes.push(field('name',t[3],draft.name??''),{id:'transports',kind:'row',children:[button('transport_http','HTTPS'),button('transport_stdio',l[0])]});
      if(draft.type==='stdio')nodes.push(text('local_help',l[5]),field('command',l[1],draft.command??''),field('args',l[2],draft.args??'[]',true),field('env',l[3],draft.env??'',true),field('cwd',l[4],draft.cwd??''));
      else nodes.push(field('url',t[4],draft.url??''),field('token',t[5],draft.token??''),field('headers',t[6],draft.headers??'',true));
      return {title:selected?t[9]:t[1],nodes,footer:[button('back',t[8]),button('save',t[7],'save',t[15]),...(selected?[button('clear_auth',t[16],'clear_auth',l[10])]:[])]};
    }
    if(mode==='import'){
      nodes.push(field('config',t[24],draft.config??'',true));
      return {title:t[2],nodes,footer:[button('back',t[8]),button('import_save',t[7],'import_save',t[25])]};
    }
    expected=config.encoded;visible=config.servers;
    for(const [i,profile]of visible.entries()){
      let probeButton:Button={...button(`probe_${i}`,t[10],'probe'),enabled:!profile.disabled};
      let hint:PageNode[]=[];
      if(isLocal(profile)&&!profile.disabled){
        try{
          if(!ctx.stdio)throw new Error('Host upgrade required');
          const review=await ctx.stdio.review(profile.name);
          probeButton={...probeButton,confirm:`${l[7]}\n${review.command} · ${l[8]}: ${review.argumentCount} · ${l[9]}: ${review.environmentCount}`,
            hostAction:{kind:'stdio.connect',server:profile.name,reviewId:review.reviewId}};
        }catch{probeButton={...probeButton,enabled:false};hint=[text(`workspace_hint_${i}`,l[6])]}
      }
      nodes.push({id:`server_${i}`,kind:'card',children:[text(`name_${i}`,profile.name),
        text(`url_${i}`,isLocal(profile)?`${l[0]} · ${profile.command}`:profile.url.split('?')[0]),text(`state_${i}`,profile.disabled?t[19]:t[18]),...hint,
        ...(tested.has(profile.name)?[text(`test_${i}`,tested.get(profile.name)!)]:[]),
        {id:`actions_${i}`,kind:'row',children:[button(`edit_${i}`,t[9],'edit'),probeButton,
          button(`toggle_${i}`,profile.disabled?t[12]:t[11],'toggle'),button(`delete_${i}`,t[13],'delete',t[14])]}]});
    }
    if(!visible.length)nodes.push(text('empty',t[26]));
    return {title:t[0],nodes,footer:[button('new',t[1]),button('import',t[2])]};
  };
}
