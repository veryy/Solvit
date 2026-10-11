# Solvit implementation workflow

Continue the existing project. Preserve data migrations, stable APK signing and Git history.
Use GitHub Actions for builds and tests; do not run local Android or TS builds/tests.
Commit each reviewable change promptly. A functional delivery includes remote validation
and a downloadable APK, not only a pushed source commit.

Before each stage, read the corresponding actual upstream implementation and its tests.
Reference checkouts are siblings of this project in `../Solvit-reference`; pinned origins
and revisions are in `docs/upstream-sources.json`. Restore them with
`python3 scripts/fetch-upstreams.py`; check them with `--verify-only`.
Do not execute upstream applications or bundle all reference repositories into the APK.

- Chat roles/context/Agent: DSH `packages/core/system-prompt` and `packages/core/agent-loop`;
  Operit `api/chat/enhance/ConversationService.kt` and its character-card configuration.
- Streaming/rendering: Operit `ui/common/markdown/RenderBatchCoordinator.kt`,
  `StreamMarkdownRenderer.kt`, and stream tests; DSH assistant streaming.
- Search: Operit `examples/various_search.ts` plus the implementation of `Tools.Net.visit`.
- Memory/compaction: Operit `api/chat/library/MemoryAutoSaveScheduler.kt` and memory library;
  DSH `packages/compaction` and token-meter contracts.
- Workspace/execution: DSH workspace/shell services and Operit terminal tools.
- Phone actions: Open-AutoGLM `phone_agent/agent.py`, actions and model client.
- Skills/Hooks/MCP packages: DSH extension packages, ZCode and ZAI coding plugin examples.

Record the exact upstream files, adopted behavior, Android adaptation and verification
evidence in a stage note. Prefer suitable existing components over speculative rewrites.
Check file-level licenses/dependencies before copying code and include required notices.
Claude Code's public repository provides examples and documentation, not its full core.

Current order and acceptance criteria: `docs/agent-direction.md`. Keep the whole roadmap;
do not replace real-model/task verification with test counts or presence of UI controls.
Never include API keys, signing material or server credentials in code or diagnostics.
