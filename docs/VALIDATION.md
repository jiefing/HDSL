# HDSL 0.2.0 验证记录

日期：2026-10-02。环境：Windows x64、JDK 21、Node 24.21.0、pnpm 10.34.0。本文记录发布前的本机验证；GitHub CI 结果见仓库的 Actions 页面。

## 真实 API 检查与官方地址适配（preview.5）

用户提供专用测试 Key 并授权真实调用后，使用隔离测试实例，通过生产 Controller 完成保存账户、绑定、启动、模型调用、停止、解绑及删除账户。Key 使用隐藏输入，仅作为运行输入，未写入源码、命令参数或报告。

- 首次真实调用返回 HTTP 404。根因是旧 Harness 的 DeepSeek 适配器使用 Chat Completions，而新版使用 Messages；账户层把同一个官方根地址传给两种协议。此前本地模拟服务接受任意路径，未能发现这一差异。失败证据 `.test-data/real-account-api-15370251544673371900/acceptance.json`。
- 已改为检查已安装的公开 DeepSeek 适配器源码来决定官方标准地址：旧版使用 `https://api.deepseek.com`，新版使用 `https://api.deepseek.com/anthropic`。不依赖写死的 Harness 版本号；第三方地址、本机地址、自定义端口及其他路径保持原样。无法识别官方协议时明确拒绝绑定。启动补丁、旧版配置桥接和最终校验使用同一实际地址。
- 修复后再次通过完整 Controller 流程测试 `0.2.0-rc.2`：调用 `deepseek-flash`，关闭思考、最大输出 8 token，只要求回复 `OK`。实际返回 `OK`，正常结束，9 个输入 token、1 个输出 token，共 10 token。直接调用模型流，不调用 Agent 工作流或生成会话标题。成功证据 `.test-data/real-account-api-18260202621898217018/acceptance.json`。
- 两次测试的界面状态和启动器日志均未出现 Key；测试进程停止，临时账户解绑、删除，临时账户文件已移除。未操作用户原有实例或账户。
- preview.5 独立 JavaFX 验收通过，0 失败、0 错误；标题及项目预览图已更新。测试报告和截图位于 `target-ui`。
- 18:19 普通 Maven `verify` 成功：138 项，130 项通过，8 项专项按配置跳过，0 失败、0 错误。新增官方标准地址映射、自定义地址保持、未知协议提前拒绝测试；本地模型服务改为严格核对请求方法和路径。
- 独立账户专项 11 项单元测试与 1 项集成测试全部通过。真实旧版使用 `POST /chat/completions`，新版使用 `POST /v1/messages`，两版自定义 OpenAI 协议使用 `POST /chat/completions`；其他路径直接返回 404。证据 `.test-data/account-binding-4770866217886840443/acceptance.md`。
- preview.5 便携程序在隔离目录打开、截图并正常退出（退出码 0）。用户原目录更新前已备份程序文件，更新后配置摘要、实例清单、Harness 运行时清单保持一致。证据 `.test-data/app-acceptance/api-fix-preview-packaged.png`、`.test-data/api-fix-preview-upgrade-verification.json`。

本轮真实付费接口验证只覆盖新版 Harness 与 DeepSeek。旧版及其他提供商的验证范围仍以下方本地模拟与运行时实测为准。

## 账户、下载中心与官网桌面版（preview.4）

- 18:04 普通 Maven `verify` 成功：135 项，127 项通过、8 项需显式启用的专项测试按配置跳过，0 失败、0 错误。跳过不计为通过。
- 独立 JavaFX 界面验收通过：侧栏「账户、实例、通用」、账户表单与绑定、Web/桌面/插件下载页、目标检查、最小窗口，以及原有背景与版本自动查询交互。截图见 `target-ui/ui-screens/23-account-dialog.png` 至 `29-accounts-minimum.png`。
- 账户模块 8 项测试通过。另行执行真实 Harness 账户测试：`0.1.0-rc.6` 和 `0.2.0-rc.2` 各验证官方 DeepSeek 与自定义 OpenAI 协议；所选模型、地址和测试密钥到达本地模拟服务器，原 settings 文件字节保持不变。禁用适配器的反例明确拒绝绑定，进程退出 78。共五次真实启动，证据 `.test-data/account-binding-17837815240942822236/acceptance.md`。只使用生成的假密钥和本机回环地址，没有读取用户密钥或调用付费模型。
- 插件目录 20 项本地 HTTP 测试通过；公开 GitHub 主题查询和 npm 安装目标核对实测通过，确认 `@dsh-packforge/dsh-pack-plugin@0.3.5`。证据 `.test-data/plugin-catalog-live/report.json`。本轮没有执行社区插件的业务功能。
- 官方入口核对为 `https://www.deepseek.com/en/download/`，Windows 文件来自 `https://download.deepseek.com/desktop/dsh-latest-windows-x64.exe`。实际完整下载 289,313,640 字节；文件版本 `0.2.0-rc.2`，Windows Authenticode 签名状态 `Valid`，签名方为 Hangzhou DeepSeek Artificial Intelligence Co., Ltd.；没有运行安装程序。
- 官方安装包本地 SHA-256 为 `d61cd8882f8b8a1251144320493ad27920d15139503eba2465b9579eee720cfc`。官网未提供发布方 SHA-256，该值是下载后的本地摘要。证据 `.test-data/official-download-research/installer-verification.json`。签名为本次手动验收，启动器本身执行大小、ETag 和可用摘要检查。
- 下载服务 14 项本地测试通过，包括根链接/opaque 链接、ETag 不匹配与缺失、已下载文件同大小损坏、摘要不符、取消后的迟到回调。官网 CDN 实测忽略错误 `If-Match`，因此下载响应另行比较 ETag。无发布摘要时保存本地 `.sha256`，再次显示已下载或打开文件夹前复核。
- 进程服务 9 项测试通过，包括关闭后拒绝新启动、启动与关闭并发、后台读取任务提交失败的清理。Controller 另有 4 项回归测试，覆盖桌面及版本查询取消、缓存损坏识别，以及关闭准备中的实例后不会再次启动进程。
- 18:05 重新执行真实 Controller 专项，旧版 `0.1.0-rc.6` 和新版 `0.2.0-rc.2` 均完成创建、启动、网页引导 HTTP 200、插件列表、停止、再次启动与端口释放。证据 `.test-data/controller-integration-6297161761851918866/acceptance.md`。
- 新便携版 `HDSL.exe` 在隔离空目录实际打开，生成 `.test-data/app-acceptance/accounts-preview-packaged.png` 并以退出码 0 正常退出。原便携目录更新前已备份程序文件；更新后配置摘要、实例及运行时目录清单保持一致，实例 001 保留。证据 `.test-data/accounts-preview-upgrade-verification.json`。
- 账户与下载的使用范围见 [accounts-and-downloads.md](accounts-and-downloads.md)。桌面安装包为独立应用；启动器账户绑定适用于其管理的 Web 实例，不会自动同步到桌面应用。

以下保留各历史版本的验证记录及当时适用范围。

## 自动版本查询与鲸鱼娘素材（preview.3）

- 17:26 普通 Maven `verify` 成功：81 项，76 项通过、5 项专项测试按配置跳过，0 失败、0 错误。新增版本查询测试使用本地 HTTP 服务，覆盖完整响应超时、响应大小限制、并发请求合并、失败保留版本缓存，以及切换源后忽略旧结果。
- 17:26 独立启用的 `UiAcceptanceTest` 通过，0 失败、0 错误。创建对话框打开后自动查询一次；慢响应不会覆盖名称和手填版本；失败可以重试或继续创建；关闭对话框后的回包不再改变它；编辑已有实例不触发自动查询。
- 版本查询独立于安装任务，不触发下载或要求预装 Harness、Node、pnpm。完整响应受 60 秒超时及 32 MiB 上限约束，避免只收到 HTTP 头后一直等待响应体。
- 最终图标和五张背景按用户提供的两张人物参考重新生成，替换短发水手服草稿。人物统一为长卷发、浅蓝发梢、白色褶边发箍和蓝白女仆装；HDSL 字标使用粗笔画、切角和直线轮廓。提示词与参考记录见 [素材记录](../assets/artwork/README.md)。
- 五张背景实际尺寸均为 1672×941；图标原图为 1254×1254，保留透明背景。Windows ICO 包含 16、24、32、48、64、128、256 像素共七个尺寸；界面 PNG 与原图逐字节相同。
- 严格 UI 验收读取实际图片，验证全部正常解码、切换和保存，兼容原有自定义图片路径。逐张查看正常及 960×650 最小窗口截图：人物头部、鲸鳍、尾巴没有边界截断；五个缩略图及操作控件均在窗口内。初雪背景脚尖与首次下载提示条轻微叠图，不影响面部和操作。
- 修正 JavaFX `BackgroundSize.cover` 对背景位置的处理，内置图片按实际窗口计算缩放尺寸并靠右对齐，自定义图片保持居中；切换背景后忽略旧图片的迟到加载回调。
- 最终素材截图位于 `target-ui/ui-screens/01-home.png`、`19-backgrounds-settings.png` 和 `background-whale-01.png` 至 `05.png`。项目预览图已更新。
- `jpackage` 生成的 preview.3 `HDSL.exe` 已在隔离空目录实际打开、生成截图并以退出码 0 正常退出。截图为 `.test-data/app-acceptance/whale-preview-packaged.png`。
- 原便携目录更新前已备份程序文件。更新后配置文件 SHA-256、实例目录清单和运行时目录清单保持一致；实例 001 和已安装的 `0.2.0-rc.2` 保留，新 EXE 与 JAR 均与干净便携包一致。证据为 `.test-data/whale-preview-upgrade-verification.json`。

本次没有重复执行旧、新 Harness 的安装和插件业务流程；相关已完成验证见下方历史记录。普通构建中的 5 项跳过不计为通过；本次 UI 专项已另行执行。

## HMCL 源码移植修订（preview.2）

用户选择直接移植 HMCL 界面并接受 GPL 发布条件后，本版移植了固定提交的 10 个 Java 控件/页面、默认蓝色主题及相关布局样式。来源和具体适配见 [HMCL_PORT.md](HMCL_PORT.md)。此前独立绘制的首页卡片和统计面板已移除；默认背景仍为 HDSL 自绘图。

- 16:56 普通 Maven `verify` 成功：74 项测试，69 项通过、5 项专项测试按配置跳过，0 失败、0 错误。后端未因本次界面移植而改动。
- 16:55 单独启用的 JavaFX UI 验收通过：七个页面、未保存设置在轮询后保留、实例菜单、中文 profile、表单与导出范围、下载阶段/真实包数量/计时、查看任务和取消。
- 在实际 JavaFX Stage 上发送事件，验证标题栏拖动、边缘缩放、双击最大化/还原、关闭请求；关闭仍交给 Controller 原有清理逻辑。断言侧栏宽 200、标题栏高 40、启动主按钮 200×55。
- 普通与最小窗口的 18 张截图已逐项生成，位于 `target-ui/ui-screens`；首页和设置截图已查看，项目预览图已替换。
- 设置中的关于区域显示 HMCL 与 HDSL 版权、版本和源码包位置；许可对话框的 GPL 全文及 HMCL 附加条款已通过 UI 检查。
- 使用 `jpackage` 生成的 preview.2 `HDSL.exe` 已在隔离空目录实际打开、截图并自动正常退出；未下载 Harness 或读取用户实例。截图为 `.test-data/app-acceptance/hmcl-port-packaged-empty.png`。

对照基于已克隆的 HMCL 固定提交源码。官方发布 JAR 下载后已校验摘要，但没有运行它，未将官网宣传图当作该提交的实际运行截图。自绘窗口使用标准 JavaFX 适配；最大化后需用按钮或双击标题还原，暂未移植拖动最大化标题栏自动还原的交互。

以下记录包含 preview.1 阶段已完成的运行时和整合包验证，保留其适用范围。

## 已完成

| 项目 | 结果与证据 |
| --- | --- |
| 普通 Maven 构建 | `mvn verify` 成功；普通测试无失败或错误，5 项需显式启用的测试默认跳过；最新修订数量见下文 |
| JavaFX 界面 | 独立启用的 UI 测试通过；七个页面、表单校验、中文 profile、取消、复制日志、导出范围、960×650 最小窗口均检查；截图在 `target-ui/ui-screens` |
| 原生 Windows 程序 | jpackage 生成 `HDSL.exe`；完整便携目录包含 Java、Node、pnpm；实际打开界面、生成截图并正常退出 |
| 旧版 Harness | `0.1.0-rc.6` 实际安装、帮助检测、Web HTTP 200、本地 fixture 插件安装及移除通过 |
| 新版 Harness | `0.2.0-rc.2` 实际安装、帮助检测、鉴权服务启动、原生插件安装及移除通过；按启动地址完成网页引导后 HTTP 200 |
| 启动器完整流程 | 生产 Controller 和 ProcessService 在两个版本上均通过创建、启动、网页引导、插件列表、停止、再次启动、端口释放 |
| 真实整合包文件 | pokemon 1.0.0 与 desktop-pack 1.0.0 的大小、SHA-256、格式、解压、bundle 顺序及依赖数量通过 |
| 真实整合包依赖 | desktop-pack 的 4 个直接依赖按精确版本恢复；实际触发过期锁错误，原锁备份哈希一致，定向刷新成功；安装脚本关闭 |
| 实例生命周期 | 7 项 Controller 测试覆盖中文名称导出/复制、所选 profile 换版本、最终目录安装、失败不登记、原实例哈希保持、并发选择和空白实例复制 |

五项需显式启用的测试已分别运行通过；不把普通构建中的“跳过”计为通过。普通测试明细和 XML 报告位于 `target/surefire-reports`，各专项构建使用独立输出目录避免并发编译互相覆盖。

## 本次实测发现并修复的问题

- 旧版 npm peer 依赖循环解析：改用 pnpm，完整安装 peer 依赖。
- 旧版 Harness 组件混装及基础库漂移：依据发布元数据固定同批次组件，使用按发行时间解析的依赖树，再保存锁文件。
- 旧版 Web 不接受 Java 默认 HTTP/2 升级：就绪检查使用 HTTP/1.1。
- 新版先监听端口、后挂载网页，启动初期返回 404：404 不视为已就绪。鉴权页面须等到启动地址可用。
- 新版 Web 启动地址包含引导令牌：仅在内存保存本实例本地地址，打开 Web 时使用，停止后清除；日志脱敏，不读取凭据文件。
- 新旧 CLI 的插件入口和 Windows shell 行为不同：使用本地参数桥接，保留 Harness 自己的依赖与 bundle 对账。
- Windows pnpm 可能创建绝对目录链接：整合包先校验解包，在最终实例目录恢复依赖，成功后才登记。
- desktop-pack 的 manifest 和锁文件声明不一致：仅对明确的过期锁错误备份并重建，其他安装错误仍失败。

## 边界

- 没有登录用户的模型账户，没有发起模型请求。模型配置和日常工作流仍需用户验收。
- 真实市场包已验证文件和依赖恢复，没有执行其中插件的业务功能。新版 Harness 的 `desktop` profile 由 Electron 专用，desktop-pack 需要进一步适配才能通过 CLI 启动；预览会提示，程序不会静默更名。
- 多 profile、vendor 和 Overture 快照主要由合成测试覆盖；没有宣称所有社区包、跨平台快照或完全离线安装均可用。
- 分享导出及复制排除凭据、会话、私人设置和补丁文件；不是完整备份。
- 未来 Harness 删除参数、改变 profile 模型、插件需要新的 Node ABI 时仍可能需要更新。能力探测和本地兼容声明降低维护频率，不能消除所有破坏性变更。
- 本机未实际运行 Linux 或 macOS GUI。CI 已配置 Windows/Linux 普通构建，但尚未推送运行。

## 可复现检查

使用 JDK 21，或通过 `scripts/build.ps1` 运行默认构建。真实运行时测试需先在 `.test-data/compatibility/tools` 准备 Node/pnpm；真实整合包测试需准备已校验的本地样本。

```powershell
mvn -B -ntp verify
mvn '-Dhdsl.buildDirectory=target-ui' '-Dtest=com.hdsl.ui.UiAcceptanceTest' '-Dhdsl.uiTest=true' test
mvn '-Dhdsl.buildDirectory=target-runtime' '-Dtest=com.hdsl.runtime.RuntimeIntegrationTest' '-Dhdsl.integration=true' test
mvn '-Dhdsl.buildDirectory=target-core-real' '-Dtest=com.hdsl.core.ControllerRuntimeIntegrationTest' '-Dhdsl.controllerIntegration=true' test
mvn '-Dhdsl.buildDirectory=target-packs' '-Dtest=com.hdsl.pack.PackRealFixtureTest' '-Dhdsl.realPackFixtures=true' test
mvn '-Dhdsl.buildDirectory=target-packs-real' '-Dtest=com.hdsl.pack.PackDependencyIntegrationTest' '-Dhdsl.packDependencyIntegration=true' test
```

本机专项证据：

- `.test-data/compatibility/integration-report.md`
- `.test-data/controller-integration-836287414762484475/acceptance.md`
- `.test-data/real-packs/extraction-report.json`
- `.test-data/real-pack-install-3f57f80b/dependency-report.json`
- `.test-data/app-acceptance/packaged-home.png`

测试目录和原始临时运行日志均不进入 Git 或便携发布包。

## 首次启动进度修订（同日）

用户首次启动时首页只显示“启动 Harness”，容易把后台下载误认为卡死。实际日志显示：发行依赖核对 15 秒，下载持续推进到 528 项后任务被取消，没有进入 Harness 启动阶段。

修订后，未安装版本显示“下载并启动”；首页固定显示阶段、四项包数量、已用时间及可用的任务入口。实例在安装时显示“下载中”。不把“已解析包数”当作总下载量计算百分比。取消任务会结束忙碌状态，完整公开元数据及包缓存可在后续安装复用。

在用户原有实例上完成正常安装路径验证：复用 528 项缓存、补下载 2 项，pnpm 安装 37.3 秒；含能力检测、启动、网页引导 HTTP 200、停止及释放端口共 58 秒。实例清单保留，没有使用模型账户或读取凭据文件。摘要见 `.test-data/user-preview-repair.md`。

本次新增首次下载状态/取消、进度计数及发行元数据缓存的回归测试。最终普通构建共 74 项，其中 69 项通过、5 项专项测试按配置跳过，0 失败、0 错误；新版 UI 专项验收也已单独通过。最小窗口截图见 `target-ui/ui-screens/18-first-launch-progress-minimum.png`。
