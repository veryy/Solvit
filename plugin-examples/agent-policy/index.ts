import {definePlugin} from '../../plugin-sdk/src/index.js';

/** A real decision from host-owned observations; truncated args cannot be reviewed reliably. */
export default definePlugin({
  tools: {},
  async agent({maxRounds}, context) {
    if (!context.observe || !context.decline) throw new Error('Observation API unavailable');
    for (let round = 0; round < maxRounds; round++) {
      const count = await context.model(round);
      if (!count) { await context.finish(round); return; }
      const proposed = await context.observe();
      for (let index = 0; index < count; index++) {
        const call = proposed.tools.find(call => call.index === index);
        if (!call || call.arguments.truncated) await context.decline(index);
        else await context.tool(index);
        const observed = await context.observe();
        if (!observed.tools.find(call => call.index === index)?.stage) throw new Error('Tool outcome missing');
      }
      await context.settle(round);
    }
  }
});
