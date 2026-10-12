# 通用插件文件交互与Skills HTML页面候选

用户要求插件用自己的HTML/JS绘制业务页面。已交付候选的记忆/任务/MCP并不包含Skills和工作区。本阶段已将Skills的列表、导入预览、详情、导出和删除移入插件自己的HTML/JS；旧API3包保留原生兼容入口。支持SKILL.md、单技能文本ZIP和JSON备份，保留角色绑定和原有数据库，不能把新页面存在当作实际系统选择器验收通过。

本阶段实际读取锁定Operit `ui/features/packages/screens/SkillConfigScreen.kt`的搜索、详情、导入状态与系统文件选择器，以及`core/tools/skill/SkillManager.kt`的元数据和ZIP导入边界。读取DSH `api/session-controller/src/skill-catalog.ts`及`tests/session-skills.host.spec.ts`的会话作用域和按需目录，以及`preset/agent-preset/tests/skills.spec.ts`的正文/引用资源契约。未找到Operit对应导入的直接单元测试，不用别的测试冒充其验收；未复制LGPL实现。DSH MIT仅采用行为边界，没有整体引入Cordis。

Android官方[Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)通过系统选择器打开/创建具体文件。计划由插件定义页面、导入预览和数据操作；通用宿主提供一次性的选择/保存，URI不进入插件，不能选取任意路径。关闭、撤权、更新使等待的文件操作失效，写入/读取前再验证。JS聊天工具和后台Hook不能调用页面文件能力。

首个检查点增加当前展示按钮的`document.open`/`document.save`元数据回归：MIME、文件名、512KiB读取上限、拒绝调用方URI/路径注入、真实人工确认和页面身份。现有宿主仅支持后台任务控制，先用远程运行确认缺口，再实现能力；本检查点不包含Skills HTML页面、新API支持或新APK，之前0.22.2候选与搜索阻塞状态保留。完整验收还须包含真实QuickJS/文件选择器→预览→导入→重开→导出→取消/确认删除、角色下模型按需加载，以及全Android/Lint/签名APK。工作区页面随后需要对话/终端作用域，原聊天、搜索、工作区及手机路线保留。

首个运行38310102521未进入测试：诊断工作流的setup-android默认要求已废弃tools包。明确使用platform-tools后，38310238560实际复现唯一新增正向用例失败，消息为Unsupported page host action；没有把环境失败算作功能复现。候选增加API11文档动作和当前按钮的一次性页面授权、通用有界UTF8/文本ZIP解码，以及QuickJS拒绝路径注入、重复消费、旧API/模型调用和撤权后结果的回归。此检查点尚未接入Android系统选择器，Skills HTML页面与新APK未实现；远程验证待运行。

系统选择器候选b545d96的远程38311374534成功，实际89个核心/插件运行时测试无失败、错误或跳过，App Kotlin编译成功；先前cb17a19的测试注册失败来自一个runBlocking方法推导出非void返回，已明确Unit后修正。当前Skills1.1.0/API11源码提供离线HTML列表/详情、系统选择后的ZIP/SKILL.md/JSON预览、同名替换和删除确认、JSON导出；保留skills.v1及全部原工具/Hook，页面写入使用CAS并拒绝陈旧同名预览。原1.0.2/API3精确哈希包保留，计划升级保留原数据/启用/授权，修改包与卸载不恢复。候选App仍为未正式发布的0.22.2，build35。新包生成、SDK/升级及真实系统选择器模拟器回合尚待验证；API11市场源码已改，在线服务器尚未部署，因此不能交付只查到API1目录的候选。

远程生成38313344225及正常一致性运行38313794010成功：实际79个SDK/2个市场测试全部通过，App内置和市场包与当前源码逐字节一致。Skills1.1.0包为255242字节，SHA-256 `585a182857fb58b2870f048cbdecef12d90b91859a09fe6870a3e0cbe359a3b2`。在线API11市场已原子切换并保留API10备份；公网API1到11的17个不同选中包逐一下载验证长度与哈希，API3到10仍返回Skills1.0.2、API11返回1.1.0。在线搜索仍为1.2.0，APK候选为1.2.1；此阶段没有更改搜索质量门禁。

真实系统选择器运行38313346912已确认取消导入不写数据，随后DocumentsUI的Downloads行ACTION_CLICK返回false，未到文件导入。测试驱动改为点击实际观察到的可见控件坐标，仍使用系统窗口和真实触摸，未模拟选择结果；新运行38313891124待确认。完整Android运行38313790898仍待结果，不能称为新APK交付。

系统选择器第三/第四次运行38314531603、38315085385的真实窗口证实文件可见而触摸未激活；没有发生数据导入。实际读取Android DocumentsUI `e033e3454cbfd7a57df6b4d7eb2ea769736b9302` 的`src/com/android/documentsui/dirlist/DirectoryFragment.java`，SelectionTracker显式仅接受FINGER/STYLUS。旧测试短MotionEvent.obtain创建UNKNOWN工具类型，被文件列表忽略，工具栏仍正常响应；修正为真实FINGER指针事件并等待系统窗口状态。原生/HTML失败现场仅使用生成的测试资料并作为诊断产物保留，未修改或模拟文件选择器结果。

实际系统选择器回合38316739694（fc45495）成功，取消不写数据、ZIP导入预览后确认保存、重开保留、导出JSON后系统选择重新导入、同名默认保留、脚本仅展示，以及删除取消/确认均已通过。导入/重开的真实截图一度发现DOM存在但内容高度塌缩，Skills/MCP改为插件CSS固定viewport并固定底部操作区；新模拟器断言检查内容/操作区高度及卡片实际可见位置。截图保存在`docs/screenshots/0.22.2/skills-html-library.png`和`skills-html-import-preview.png`，不是设计稿。

当前候选0.22.2/build36：Skills1.1.1/API11，255403字节，SHA-256 `83a8912346d3e120f8795a5f35a7ed41a97d90005706305031f3fe34e68bbebf`；MCP1.1.1/API10，37542字节，SHA-256 `1d437a9297e6d570ce34d8c306e2acae02d0b899a94f3d058f27835073a23a66`。原1.1.0字节作为legacy与升级测试资源保留，原始精确哈希可以升级，修改/卸载不恢复。正常SDK一致性38316833290成功，服务器修复包仅准备到独立stage，尚未切换。完整运行38316830794因新增回归夹具包含连接协议不接受的id字段失败；2d4ce42去掉错误夹具字段，没有放松生产校验。完整Android验证待当前运行结果。

最终候选源码2d4ce42的38317347510全部main检查成功：实际297个单元、45个模拟器测试无失败、错误或跳过，Lint/签名/APK及覆盖升级后配置与聊天保留通过。MCP的内容/操作区与两卡片可见位置也已通过，修复包现已部署；17个API1..11选中包以及两份原HTML1.1.0公网下载分别核对，备份保留。APK为23370940字节，SHA-256 `30dd14d6409de8488cdaacd813dd2bd76067bf2981b1b3fe27c47281a6c689fb`。main未执行标签的发布/搜索门禁，正式Release仍未创建；工作区旧页面、真实模型角色/流式/任务完成及真机验收仍保留。原生选择器测试重新导入验证到同名预览及默认保留，真正替换/陈旧冲突和角色按需加载另由SDK验证，不冒充真机/真实模型。
