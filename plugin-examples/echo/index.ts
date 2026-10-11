import { definePlugin, textArgument } from '../../plugin-sdk/src/index';
export default definePlugin({
  tools: {
    async echo(args) { return { text: textArgument(args, 'text') }; },
  },
});
