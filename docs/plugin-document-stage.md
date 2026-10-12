# 通用插件文件交互与Skills HTML页面候选

用户要求插件用自己的HTML/JS绘制业务页面。已交付候选的记忆/任务/MCP并不包含Skills和工作区。当前Skills的导入、预览、导出和删除仍在`AgentLibraryDialog.kt`，支持SKILL.md、单技能文本ZIP和JSON备份；迁移须保留这些功能、角色绑定和原有数据库，不缩减成文本框。

本阶段实际读取锁定Operit `ui/features/packages/screens/SkillConfigScreen.kt`的搜索、详情、导入状态与系统文件选择器，以及`core/tools/skill/SkillManager.kt`的元数据和ZIP导入边界。读取DSH `api/session-controller/src/skill-catalog.ts`及`tests/session-skills.host.spec.ts`的会话作用域和按需目录，以及`preset/agent-preset/tests/skills.spec.ts`的正文/引用资源契约。未找到Operit对应导入的直接单元测试，不用别的测试冒充其验收；未复制LGPL实现。DSH MIT仅采用行为边界，没有整体引入Cordis。

Android官方[Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)通过系统选择器打开/创建具体文件。计划由插件定义页面、导入预览和数据操作；通用宿主提供一次性的选择/保存，URI不进入插件，不能选取任意路径。关闭、撤权、更新使等待的文件操作失效，写入/读取前再验证。JS聊天工具和后台Hook不能调用页面文件能力。

首个检查点增加当前展示按钮的`document.open`/`document.save`元数据回归：MIME、文件名、512KiB读取上限、拒绝调用方URI/路径注入、真实人工确认和页面身份。现有宿主仅支持后台任务控制，先用远程运行确认缺口，再实现能力；本检查点不包含Skills HTML页面、新API支持或新APK，之前0.22.2候选与搜索阻塞状态保留。完整验收还须包含真实QuickJS/文件选择器→预览→导入→重开→导出→取消/确认删除、角色下模型按需加载，以及全Android/Lint/签名APK。工作区页面随后需要对话/终端作用域，原聊天、搜索、工作区及手机路线保留。

首个运行38310102521未进入测试：诊断工作流的setup-android默认要求已废弃tools包。明确使用platform-tools后，38310238560实际复现唯一新增正向用例失败，消息为Unsupported page host action；没有把环境失败算作功能复现。候选增加API11文档动作和当前按钮的一次性页面授权、通用有界UTF8/文本ZIP解码，以及QuickJS拒绝路径注入、重复消费、旧API/模型调用和撤权后结果的回归。此检查点尚未接入Android系统选择器，Skills HTML页面与新APK未实现；远程验证待运行。
