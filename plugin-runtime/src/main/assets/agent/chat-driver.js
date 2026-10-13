/* Solvit trusted bundled Agent driver. No direct network, storage or OS access. */
globalThis.solvitAgent = Object.freeze({
  run: async function (maxRounds) {
    for (let round = 0; round < maxRounds; round++) {
      const calls = await __solvitAgentCommand('model', round);
      if (calls === 0) {
        await __solvitAgentCommand('finish', round);
        return;
      }
      for (let index = 0; index < calls; index++) {
        await __solvitAgentCommand('tool', index);
      }
      await __solvitAgentCommand('settle', round);
    }
  }
});
