import { definePlugin } from '../../plugin-sdk/src/index';
export default definePlugin({
  async beforeModel(_input, ctx) {
    const now = await ctx.clock.now();
    return '设备当前时间（每轮聊天开始时重新读取）：' + JSON.stringify(now) +
      '\n回答日期和时间时使用此信息，不要说自己无法获取当前时间。需要回答时刻的精确时间时可调用 dev_solvit_clock_current_time。设备时钟可能由用户调整。';
  },
  tools: { async current_time(_args, ctx) { return ctx.clock.now(); } },
});
