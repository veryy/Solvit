import {definePlugin} from '../../plugin-sdk/src/index';
import {gitPage} from './pages';
export default definePlugin({tools:{
 status:(args,ctx)=>ctx.git!.call('status',args),
 diff:(args,ctx)=>ctx.git!.call('diff',args),
 history:(args,ctx)=>ctx.git!.call('history',args),
},pages:{main:gitPage()}});
