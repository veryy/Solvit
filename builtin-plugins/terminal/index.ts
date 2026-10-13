import { definePlugin } from '../../plugin-sdk/src/index';
export default definePlugin({ pages: { main: ({locale}) => ({title: locale.startsWith('zh') ? '交互终端' : 'Terminal', nodes: [], footer: []}) }, tools: {
  terminal_open: (args, ctx) => ctx.terminal!.call('open', args),
  terminal_status: (args, ctx) => ctx.terminal!.call('status', args),
  terminal_input: (args, ctx) => ctx.terminal!.call('input', args),
  terminal_interrupt: (args, ctx) => ctx.terminal!.call('interrupt', args),
  terminal_close: (args, ctx) => ctx.terminal!.call('close', args),
} });
