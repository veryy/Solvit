import type {PageNode,PluginPageInput,PluginPageView,PluginContext} from '../../plugin-sdk/src/index';
import {readConnections,validateConnections,importConnections,type Connection} from './config';
const words={
  zh:['MCP 服务器','添加连接','导入配置','名称','HTTPS 地址','Bearer 令牌（留空保留）','请求头 JSON（留空保留）','保存','返回','编辑','测试连接','停用','启用','删除','确定删除此连接？','保存连接配置？','清除认证','清除该连接的令牌和请求头？','已启用','已停用','配置已变化，请返回重新编辑。','操作失败，请检查名称、HTTPS 地址和配置格式。','连接测试成功','连接测试失败；请检查地址、认证和服务器是否可用。','粘贴 mcpServers JSON','导入将替换所有连接，确定继续？','暂无服务器，请添加或导入。'],
  en:['MCP servers','Add server','Import config','Name','HTTPS endpoint','Bearer token (blank keeps existing)','Headers JSON (blank keeps existing)','Save','Back','Edit','Test connection','Disable','Enable','Delete','Delete this connection?','Save connections?','Clear authentication','Clear this server’s token and headers?','Enabled','Disabled','Configuration changed. Go back and edit again.','Action failed. Check name, HTTPS endpoint and configuration format.','Connection verified','Connection failed. Check endpoint, authentication and server availability.','Paste mcpServers JSON','Import replaces all connections. Continue?','No servers yet. Add or import one.'],
  ja:['MCPサーバー','接続を追加','設定をインポート','名前','HTTPSアドレス','Bearerトークン（空欄は維持）','ヘッダーJSON（空欄は維持）','保存','戻る','編集','接続テスト','無効化','有効化','削除','この接続を削除しますか？','接続設定を保存しますか？','認証をクリア','トークンとヘッダーを削除しますか？','有効','無効','設定が変更されました。戻って再編集してください。','名前、HTTPSアドレスと設定形式を確認してください。','接続確認済み','接続に失敗しました。アドレスと認証を確認してください。','mcpServers JSONを貼り付け','すべての接続を置き換えますか？','サーバーがありません。追加してください。'],
  ko:['MCP 서버','연결 추가','설정 가져오기','이름','HTTPS 주소','Bearer 토큰 (비우면 유지)','헤더 JSON (비우면 유지)','저장','뒤로','편집','연결 테스트','비활성화','활성화','삭제','연결을 삭제할까요?','연결 설정을 저장할까요?','인증 지우기','토큰과 헤더를 지울까요?','활성화됨','비활성화됨','설정이 변경됐습니다. 다시 편집하세요.','이름, HTTPS 주소와 설정 형식을 확인하세요.','연결 확인됨','연결 실패. 주소와 인증을 확인하세요.','mcpServers JSON 붙여넣기','모든 연결을 교체할까요?','서버가 없습니다. 추가하세요.'],
  es:['Servidores MCP','Añadir conexión','Importar configuración','Nombre','Dirección HTTPS','Token Bearer (vacío conserva)','Cabeceras JSON (vacío conserva)','Guardar','Volver','Editar','Probar conexión','Desactivar','Activar','Eliminar','¿Eliminar esta conexión?','¿Guardar conexiones?','Borrar autenticación','¿Borrar token y cabeceras?','Activo','Inactivo','La configuración cambió. Vuelve a editar.','Revisa nombre, dirección HTTPS y formato.','Conexión verificada','Conexión fallida. Revisa dirección y autenticación.','Pegar JSON mcpServers','¿Reemplazar todas las conexiones?','Sin servidores. Añade o importa uno.'],
  fr:['Serveurs MCP','Ajouter une connexion','Importer','Nom','Adresse HTTPS','Jeton Bearer (vide pour conserver)','En-têtes JSON (vide pour conserver)','Enregistrer','Retour','Modifier','Tester','Désactiver','Activer','Supprimer','Supprimer cette connexion ?','Enregistrer les connexions ?','Effacer l’authentification','Effacer le jeton et les en-têtes ?','Actif','Inactif','La configuration a changé. Revenez la modifier.','Vérifiez le nom, l’adresse HTTPS et le format.','Connexion vérifiée','Échec de connexion. Vérifiez l’adresse et l’authentification.','Coller le JSON mcpServers','Remplacer toutes les connexions ?','Aucun serveur. Ajoutez ou importez une connexion.'],
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
    const t=words[input.locale.split('-')[0] as keyof typeof words]??words.zh;
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
      else if(action==='new'){expected=config.encoded;selected=null;draft={name:'',url:'',token:'',headers:''};mode='edit'}
      else if(action==='import'){expected=config.encoded;draft={config:''};mode='import'}
      else if(action==='edit'){if(config.encoded!==expected)throw new Error('CONFLICT');const profile=visible[Number(event!.nodeId.slice(5))];if(!profile)throw new Error('INVALID');
        expected=config.encoded;selected=profile.name;draft={name:profile.name,url:profile.url,token:'',headers:''};mode='edit'}
      else if(action==='save'&&mode==='edit'){
        if(config.encoded!==expected)throw new Error('CONFLICT');
        const previous=config.servers.find(profile=>profile.name===selected);
        const candidate={name:draft.name?.trim(),url:draft.url?.trim(),token:draft.token||previous?.token||'',
          headers:draft.headers?.trim()?JSON.parse(draft.headers):previous?.headers??{},disabled:previous?.disabled??false};
        const servers=config.servers.filter(profile=>profile.name!==selected);
        servers.push(...validateConnections([candidate]));await store(servers);mode='list';draft={};
      }else if(action==='import_save'&&mode==='import'){
        draft={config:event!.fields.config??''};await store(importConnections(draft.config));mode='list';draft={};
      }else if(action==='clear_auth'&&mode==='edit'){
        if(config.encoded!==expected)throw new Error('CONFLICT');
        await store(config.servers.map(profile=>profile.name===selected?{...profile,token:'',headers:{}}:profile));mode='list';draft={};
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
      nodes.push(field('name',t[3],draft.name??''),field('url',t[4],draft.url??''),field('token',t[5],draft.token??''),field('headers',t[6],draft.headers??'',true));
      return {title:selected?t[9]:t[1],nodes,footer:[button('back',t[8]),button('save',t[7],'save',t[15]),...(selected?[button('clear_auth',t[16],'clear_auth',t[17])]:[])]};
    }
    if(mode==='import'){
      nodes.push(field('config',t[24],draft.config??'',true));
      return {title:t[2],nodes,footer:[button('back',t[8]),button('import_save',t[7],'import_save',t[25])]};
    }
    expected=config.encoded;visible=config.servers;
    for(const [i,profile]of visible.entries())nodes.push({id:`server_${i}`,kind:'card',children:[text(`name_${i}`,profile.name),
      text(`url_${i}`,profile.url.split('?')[0]),text(`state_${i}`,profile.disabled?t[19]:t[18]),...(tested.has(profile.name)?[text(`test_${i}`,tested.get(profile.name)!)]:[]),
      {id:`actions_${i}`,kind:'row',children:[button(`edit_${i}`,t[9],'edit'),{...button(`probe_${i}`,t[10],'probe'),enabled:!profile.disabled},
        button(`toggle_${i}`,profile.disabled?t[12]:t[11],'toggle'),button(`delete_${i}`,t[13],'delete',t[14])]}]});
    if(!visible.length)nodes.push(text('empty',t[26]));
    return {title:t[0],nodes,footer:[button('new',t[1]),button('import',t[2])]};
  };
}
