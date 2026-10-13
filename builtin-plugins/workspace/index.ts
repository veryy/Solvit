import { definePlugin } from '../../plugin-sdk/src/index';
import {workspacePage} from './pages';
import {renderInstructions} from './instructions';
import {changesPage} from './changes';
export default definePlugin({
  async beforeModel(input,ctx){
    if(input.settings?.projectRules==='false')return '';
    return renderInstructions(await ctx.workspace!.instructions!());
  },
  pages:{main:workspacePage(),changes:changesPage()},tools: {
  info: (args, ctx) => ctx.workspace!.call('info', args),
  read_file: (args, ctx) => ctx.workspace!.call('read', args),
  write_file: (args, ctx) => ctx.workspace!.call('write', args),
  create_shell: (args, ctx) => ctx.workspace!.call('createShell', args),
  shell_start: (args, ctx) => ctx.workspace!.call('start', args),
  shell_status: (args, ctx) => ctx.workspace!.call('status', args),
  shell_stop: (args, ctx) => ctx.workspace!.call('cancel', args),
} });
