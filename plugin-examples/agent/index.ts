import { definePlugin } from '../../plugin-sdk/src/index.js';

/** No native loop proxy: this plugin requests each individual model/tool step. */
export default definePlugin({
  tools: {},
  async agent({maxRounds}, context) {
    for (let round = 0; round < maxRounds; round++) {
      const calls = await context.model(round);
      if (calls === 0) { await context.finish(round); return; }
      for (let index = 0; index < calls; index++) await context.tool(index);
      await context.settle(round);
    }
  }
});
