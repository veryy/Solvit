import type {PluginContext,PluginPageInput,PluginPageView,PageNode,PageHostAction} from '../../plugin-sdk/src/index';
type Environment={supported:boolean;installed:boolean;stage:string;bytes:number;total:number|null;output:string;failure:string|null};
type Snapshot={workspaceId:string|null;shells:{id:string;name:string;runtime?:string}[]};
type Control=Extract<PageHostAction,{kind:'workspace.perform'}>;
const texts={
 zh:['Linux 开发环境','无需 root 的 Ubuntu 24.04。安装后自带 Python、venv、Node.js/npm，文件与当前工作区共享。它不会授予 Android 系统权限。','当前设备架构暂不支持 Linux；Android Shell 仍可使用。','下载并安装 Ubuntu','停止安装','刷新','安装 Python / pip','安装 Node.js / npm','安装 Java 17','新建 Ubuntu Shell','请先创建或绑定工作区，再添加 Ubuntu Shell。','已安装','尚未安装','下载和安装需要网络与手机存储空间。继续？','安装记录','取消或失败后可以重试；旧环境会保留。'],
 en:['Linux development environment','Ubuntu 24.04 without root. Includes Python, venv and Node.js/npm. Files share the current workspace. This does not grant Android system permissions.','Linux is unavailable for this device architecture. Android Shell remains available.','Download and install Ubuntu','Stop installation','Refresh','Install Python / pip','Install Node.js / npm','Install Java 17','New Ubuntu Shell','Create or bind a workspace before adding an Ubuntu Shell.','Installed','Not installed','Downloading and installing uses your network and storage. Continue?','Installation log','Retry after failure or cancellation; the previous environment is retained.'],
 ja:['Linux 開発環境','root 不要の Ubuntu 24.04。Python、venv、Node.js/npm を含み、現在のワークスペースとファイルを共有します。Android のシステム権限は付与されません。','この端末のアーキテクチャは未対応です。Android Shell は利用できます。','Ubuntu をダウンロードしてインストール','インストール停止','更新','Python / pip をインストール','Node.js / npm をインストール','Java 17 をインストール','Ubuntu Shell を作成','先にワークスペースを作成または関連付けてください。','インストール済み','未インストール','ネットワークとストレージを使用します。続けますか？','インストールログ','失敗や中止後に再試行できます。既存環境は保持されます。'],
 ko:['Linux 개발 환경','root 없이 Ubuntu 24.04를 사용합니다. Python, venv, Node.js/npm을 포함하며 작업 공간 파일을 공유합니다. Android 시스템 권한은 부여하지 않습니다.','이 기기 아키텍처는 지원하지 않습니다. Android Shell은 사용할 수 있습니다.','Ubuntu 다운로드 및 설치','설치 중지','새로 고침','Python / pip 설치','Node.js / npm 설치','Java 17 설치','Ubuntu Shell 만들기','먼저 작업 공간을 만들거나 연결하세요.','설치됨','설치 안 됨','네트워크와 저장 공간을 사용합니다. 계속할까요?','설치 로그','실패하거나 중지하면 재시도할 수 있습니다. 기존 환경은 유지됩니다.'],
 es:['Entorno de desarrollo Linux','Ubuntu 24.04 sin root. Incluye Python, venv y Node.js/npm y comparte archivos con el espacio actual. No concede permisos del sistema Android.','Arquitectura no compatible. Android Shell sigue disponible.','Descargar e instalar Ubuntu','Detener instalación','Actualizar','Instalar Python / pip','Instalar Node.js / npm','Instalar Java 17','Crear Ubuntu Shell','Crea o vincula un espacio primero.','Instalado','No instalado','La instalación usa red y almacenamiento. ¿Continuar?','Registro de instalación','Puedes reintentar tras fallos o cancelación. Se conserva el entorno anterior.'],
 fr:['Environnement Linux','Ubuntu 24.04 sans root. Inclut Python, venv et Node.js/npm et partage les fichiers de cet espace. Aucun droit système Android supplémentaire.','Architecture non prise en charge. Android Shell reste disponible.','Télécharger et installer Ubuntu','Arrêter l’installation','Actualiser','Installer Python / pip','Installer Node.js / npm','Installer Java 17','Créer un Ubuntu Shell','Créez ou liez un espace de travail d’abord.','Installé','Non installé','Utilise le réseau et le stockage. Continuer ?','Journal d’installation','Réessayez après un échec ou une annulation. L’ancien environnement est conservé.']
};
const stages={
 zh:['待安装','下载中','解包中','安装基础软件','安装软件','已就绪','已停止','安装失败'],
 en:['Idle','Downloading','Extracting','Installing base packages','Installing software','Ready','Stopped','Failed'],
 ja:['待機','ダウンロード中','展開中','基本ソフトをインストール中','ソフトをインストール中','準備完了','停止','失敗'],
 ko:['대기','다운로드 중','압축 해제 중','기본 프로그램 설치 중','프로그램 설치 중','준비됨','중지됨','실패'],
 es:['En espera','Descargando','Extrayendo','Instalando paquetes básicos','Instalando software','Listo','Detenido','Fallido'],
 fr:['En attente','Téléchargement','Extraction','Installation des paquets','Installation du logiciel','Prêt','Arrêté','Échec']
};
const keys=['IDLE','DOWNLOADING','EXTRACTING','INITIALIZING','INSTALLING_SOFTWARE','READY','CANCELLED','FAILED'];
export function environmentPage(){
 let message='';
 return async({locale,event}:PluginPageInput,ctx:PluginContext):Promise<PluginPageView>=>{
  const lang=locale.split(/[-_]/)[0] as keyof typeof texts,t=texts[lang]??texts.en,stageLabels=stages[lang]??stages.en;
  const api=ctx.workspacePage;if(!api)throw Error('Environment page needs API21');
  if(event&&event.action!=='refresh')try{await api.perform();message=''}catch(e){message=e instanceof Error?e.message:String(e)}
  const env=await api.query('environmentStatus') as unknown as Environment;
  const snapshot=await api.query('snapshot') as unknown as Snapshot;
  const busy=['DOWNLOADING','EXTRACTING','INITIALIZING','INSTALLING_SOFTWARE'].includes(env.stage);
  const text=(id:string,value:string,tone:'body'|'title'|'caption'='body'):PageNode=>({id,kind:'text',text:value,tone});
  const control=(id:string,label:string,operation:Control['operation'],args:Control['arguments']={},enabled=true):PageNode=>
   ({id,kind:'button',text:label,action:id,enabled,confirm:operation==='cancelEnvironment'?undefined:t[13],hostAction:{kind:'workspace.perform',operation,arguments:args}});
  const nodes:PageNode[]=[text('intro',t[1]),text('environment_meta',JSON.stringify({busy,stage:env.stage,installed:env.installed,supported:env.supported,bytes:env.bytes,total:env.total})),
   text('status',`${env.installed?t[11]:t[12]} · ${stageLabels[keys.indexOf(env.stage)]??env.stage}`,'title')];
  if(message||env.failure)nodes.push(text('failure',message||env.failure||''));
  if(!env.supported)nodes.push(text('unsupported',t[2]));
  else {
   if(busy){
    if(env.stage==='DOWNLOADING'&&env.bytes>0)nodes.push(text('progress',`${(env.bytes/1048576).toFixed(1)} MiB${env.total?` / ${(env.total/1048576).toFixed(1)} MiB · ${Math.min(100,Math.floor(env.bytes/env.total*100))}%`:''}`));
    nodes.push(control('stop',t[4],'cancelEnvironment'));
   }else if(!env.installed)nodes.push(control('install',t[3],'installEnvironment'));
   if(env.installed){
    for(const [index,software]of ['PYTHON','NODE','JAVA'].entries())nodes.push(control(`software_${software}`,t[6+index],'installSoftware',{software},!busy));
    if(snapshot.workspaceId)nodes.push(control('newShell',t[9],'createLinuxShell',{name:`Ubuntu ${snapshot.shells.filter(s=>s.runtime==='UBUNTU').length+1}`},!busy));
    else nodes.push(text('workspaceNeeded',t[10]));
   }
  }
  nodes.push(text('retryHint',t[15],'caption'));
  if(env.output)nodes.push(text('logTitle',t[14],'title'),text('log',env.output));
  return {title:t[0],nodes,footer:[{id:'refresh',kind:'button',text:t[5],action:'refresh'}]};
 };
}
