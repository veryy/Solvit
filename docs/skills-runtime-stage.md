# Skills 资源目录与 Ubuntu 执行

2026-10-16。上一轮0.42.3已发布；本阶段尚未发布，不把导入或准备资源等同于脚本执行成功。

## 上游实际核对

- DSH `d743267388641bc76f17c45ce8b4c231aed1d32c`：`packages/skill/tool-skill/src/index.ts`及`tests/tool-skill.spec.ts`，`skill/src/index.ts`的resourceBase与目录渲染，`skill-filesystem/tests/skill-filesystem.spec.ts`的真实目录/符号链接/读取失败。采用按角色发现、加载时返回资源基准、执行工具独立授权的分工，不把导入触发器变成自动执行。
- 同提交`tool-workspace-dependencies/src/index.ts`：显式解释器路径和环境信息，不声称QuickJS提供Node进程或模块API。
- ZAI coding plugins `0446d0bb0bc537d97d3ab3664c4b8b9c4a0e1254`：`plugins/glm-plan-bug/skills/case-feedback-skill/SKILL.md`及`scripts/submit-feedback.mjs`。实际示例依赖Node内置https及显式认证环境，要求只执行一次。只阅读，没有运行反馈脚本、发送消息或采用其认证配置。
- Operit固定`dbf71916`/TerminalCore`04586898`的PRoot与软件安装链沿用[Linux阶段](linux-environment-stage.md)；不是复制预编译程序。

DSH为MIT，ZAI包为Apache-2.0，Operit参考为LGPL-3.0；本阶段独立适配，没有复制这些实现源码。

## 已提交的第一步

新增通用工作区文本资源发布原语：最多40文件、单个32KiB/合计220KiB，严格UTF-8与路径校验，在私有缓存准备后使用现有原生目录发布器原子、不覆盖地发布到新UUID目录。返回工作区相对路径及源内容摘要，不赋予执行权限，不写入既有项目文件。取消/失败只清理自己的私有暂存；已经发布的副本归工作区所有，不能自动删除其后续修改。突然结束遗留的是系统可清理缓存，不自动重放发布；缓存目录有容量上限。普通应用UID下的Shell不是恶意代码安全沙箱。

新增6项远程单元：Unicode及稳定摘要、不执行/不覆盖、路径/容量拒绝、取消清理、根目录变化/符号链接、并发目标及发布后撤权保留。尚未运行。下一步接插件授权桥、Skills TS资源准备和Ubuntu实际Agent验证，未完成前不更新用户指南为已支持运行。

API31候选新增`workspace.resources`和单工具`requiresWorkspace`声明。该字段只控制是否显示工具，不授予工作区/执行权限；普通聊天仍可列出/读取Skills，准备资源必须有绑定。通用授权桥核对原始确认中的资源名、当前插件代码/权限/启用状态、角色、聊天绑定和一次性调用；文件正文来自已授权插件，目的路径由宿主选择，不接受模型填入额外文件正文/路径。插件可以提供自己的资源格式，Kotlin不解析SKILL.md或硬编码Skills业务。新增3项桥授权及API声明/SDK构建检查，尚待远程执行。

Skills1.2.0/API31候选增加`skills_prepare`。先加载正文，按需准备当前角色允许技能的完整文本包，返回`resourceBase`与工作区相对路径，再复用已授权Ubuntu Shell；导入、加载、准备都不会运行脚本。补充.mjs/.cjs文本资源，TS仍需要显式运行器，不声称兼容所有桌面技能或二进制资产。旧Skills1.1.1原始字节保留为API1–30市场包及APK兼容资产；已安装用户不自动增加workspace授权。既有更早原始种子只升级到兼容包，明确更新新版才重审权限。新增JS角色范围/路径和不执行检查，页面及聊天旧行为保留。

## 远程检查进展

第一步abd694fb的[诊断38765793558](https://github.com/veryy/Solvit/actions/runs/38765793558)通过；已下载原始XML，115套/487用例，无失败、错误或跳过，包含6项资源发布测试。归档11988632240，SHA256 `a0c10f110d94cfb7d44d3b79ca11a991941484f3eff38e1ec59f7ec379c5f0ee`。

API31桥首次诊断38766584222失败于既有目录测试仍期待hostApi30；TS首次38767294289失败于旧文案断言。dc650f7a修正目录期望并保留原来“不会授予”的表述，没有放宽权限或删掉检查。[TS38768256005](https://github.com/veryy/Solvit/actions/runs/38768256005)实际141/141及市场6/6通过；远程包11988599810完整ZIP核对后采用，摘要 `62c1178397e7eade48ec2c845bb7bb9508aeb7e4df2d58130d5aa2b415d9fa0a`，只有Skills主包/内置包变化。后续增加旧API11–30保持Skills1.1.1完整字节的市场测试及旧版显式更新授权回归，待正式构建。

新增实际Ubuntu设备探针：QuickJS Skills正文加载、资源准备、info选择Ubuntu Shell、运行Python/Node相对资源、检查真实失败退出码、写入缺失输入、再次执行并读取输出，包含Unicode及Shell元字符的字面量参数；导入/准备不得执行脚本，技能库原始脚本保持。使用确定性Provider驱动真实工具，不当作真实模型任务。设备日志缺任一实际执行标记则工作流失败。19fc3d17启动38769171362，尚待结果。

[19fc3d17原生诊断38769176196](https://github.com/veryy/Solvit/actions/runs/38769176196)已通过并下载独立核对：117套/491实际用例，包含6项发布、3项授权桥、API声明、真实QuickJS普通聊天Skills回归，无失败/错误/跳过。归档11990069578 SHA256 `2e004b7d1ab3e35abc4aafaa9cc7c91a3333f71eefe641d233425f389a7875e9`。不把此原生单元结果当作Ubuntu脚本执行证明。

19fc3d17的Ubuntu诊断38769171362已失败：生产环境下载、Java和此前MCP链路执行后，Skills资源准备返回了Agent脱敏的UNKNOWN，后续JSON解析失败；不能据此宣称脚本运行通过。e81062ad增加同一真实QuickJS工具的最小直接调用，在Agent脱敏前保留测试异常原因，仍不记录账号配置或密钥。尚待重新远程诊断。

1b9bbd44的[TS38769510870](https://github.com/veryy/Solvit/actions/runs/38769510870)141/141、市场7/7全部通过。API31市场已经私有before/next逐项验证：API1..30目录内容完全保持、55个历史包完整字节不变；切换后服务active，备份`/opt/solvit-market/.backup-api31-1792082197283`。公网重新取得31个目录、59次完整包下载/56个不同版本，摘要和大小全部匹配。Skills1.2.0/API31主包258398字节，SHA256 `541c979ba8038b50a886680cfeb51d1bc59f66d51cc193186de687c009e3bfad`。SSH等待超时后没有重复切换或重启检查，读取原任务完成的verified.json；检查脚本见scripts/verify-market-api31.mjs。新APK仍待Ubuntu修复和完整发布门禁。

e81062ad最小复现[38770915001](https://github.com/veryy/Solvit/actions/runs/38770915001)揭示原异常为“资源目录身份无效”。Android应用filesDir有系统路径别名，工作区旧directory()已按canonical根验证，但新增发布原语要求绝对路径本身等于canonical；宿主传入了别名。修复为仅在通过既有directory()根检查后传入canonicalFile，发布原语继续严格检查NOFOLLOW和目录身份，不允许用户任意符号链接，不修改模型参数或覆盖规则。保留最小调用和完整Agent失败→修复→读取断言，继续远程验证。

版本准备3acbfa2b/v0.43.0/build83，完整发布[38772449349](https://github.com/veryy/Solvit/actions/runs/38772449349)已启动。5010efb3单独Linux重跑38772190191已取消，改由正式标签的同一Ubuntu测试继续，避免重复安装；这不算通过。正式流程仍包含全部单元、Lint、固定签名APK、独立设备报告门禁、UI/恢复/升级、TS/市场/公网搜索以及实际Ubuntu探针，尚未发布。不会用取消的运行替代任何门禁。


0.43.0/38772449349的Android单元、Lint、APK、TS/市场/公网检查已通过，但Ubuntu探针在启动Shell后取不到job JSON，发布被阻止。资源准备和info已实际经过真实QuickJS通过；对照生产App与AndroidExecution.authorized发现新探针使用ToolsPlugin默认装配，未注入SystemExecutionService，确认描述缺executionMode被严格身份检查拒绝。修正仅测试：注册真实workspaces.execution并启用ToolsPlugin(enforceSystem=true)，增加模型工具描述的APP身份断言，不修改或放宽产品授权。版本递增0.43.1/build84，不移动已失败标签或替换资产。


0.43.0原始单元报告11992981393已完整下载独立解析：611用例，无失败/错误/跳过，归档SHA256 `191a55789ffc77a3f2f420b6356b3f5d8afdebc8bdee3a33cd36d24b86932504`。这不是0.43.1的验证结果，也不抵消Ubuntu失败。59a6174e/v0.43.1已启动[完整发布38774027771](https://github.com/veryy/Solvit/actions/runs/38774027771)，所有门禁重新执行。

0.43.1/38774027771的构建、TS/市场/公网搜索与Ubuntu检查已通过。正式单元报告11992618766已独立完整核对：611用例、无失败/错误/跳过；归档SHA256 `993500931d1fb1aaaa3cf9d7caecfe97b6051c2897f6e6aea64f20aea7c65f31`。构建日志实际显示4项发布报告门禁回归通过，Lint/assemble成功，签名仍为`f076abf86de542063f8655abf9021cb88b642ab9b664cafc46c0f16b4303bcc3`。

正式APK产物11992493971完整分段下载/组装并校验归档摘要`64d00b6d11b1741ee6b40e8fa33e5fc961005c591231543774b8fbfe8a65ec16`及CRC；实际APK58955031字节、SHA256 `7e078b242d6a4c173c8f648c19a336f89332d2cebc1d225517f04fda4267910f`，内置Skills1.2.0与兼容1.1.1完整字节均与提交一致，ARM64/x86_64 PRoot ELF均存在。Linux源码产物11991674948完整核对后，内层源码ZIP1072711字节、14条目、SHA256 `6ce7c9022da87c389ea8f4c8ae8d8f256b3c27e54faf73e2fdec929dc1cb11db`。尚未完成UI/覆盖升级/中断恢复及公开发布，不把本地产物当作已发布版本。
