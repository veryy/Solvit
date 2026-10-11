import { definePlugin, textArgument } from '../../plugin-sdk/src/index';
export default definePlugin({ tools: {
  draft_plugin: async (args, ctx) => {
    textArgument(args, 'manifestJson', 32000); textArgument(args, 'source', 200000);
    return ctx.plugins!.draft({ manifestJson: args.manifestJson as string, source: args.source as string });
  },
} });
