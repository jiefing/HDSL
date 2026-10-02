# HDSL — Hello DeepSeek Harness Launcher

**HDSL 0.2.0**

[下载 Windows x64 便携包及源码](https://github.com/jiefing/HDSL/releases/tag/v0.2.0)。解压后运行 `HDSL.exe`，请保留整个目录。

用于 Windows 的 DeepSeek Harness 启动器。新版直接移植并适配 HMCL 的 JavaFX 窗口框架、侧栏、列表控件和首页启动区域，接入 Harness 实例、运行时、整合包、插件及任务管理。

![HDSL 预览](assets/preview-v02.png)

界面示例使用隔离的演示数据；真实运行验证见 [验证记录](docs/VALIDATION.md)。

## 功能

- 侧栏按账户、实例、通用分组。统一保存 API Key 账户，使用 Windows 当前用户的 DPAPI 加密；可给不同 HDSL 实例绑定账户。
- 每个实例独立保存工作目录、DSH_HOME、DSH_AGENTS_HOME、profile 和端口。
- 打开“创建实例”窗口即在后台查询 Harness 版本，可继续手动填写和创建；查询失败时保留输入与已有列表。运行时按精确版本隔离安装，原实例不会随列表刷新自动升级。
- 读取实际 CLI 帮助判断启动能力，并允许本地兼容性声明。保留插件未知字段，由当前 Harness 和 pnpm 管理依赖。
- 导入 PackForge `.dspack` 与同平台 Overture format 1 快照；导出当前 profile 或多个自定义 profile 的分享包。
- 切换 Harness 版本时复制所选 profile，保留原实例；实例删除和失败导入保留在回收目录。
- 后台任务、取消、日志、代理配置和独立进程树停止。
- 五张内置鲸鱼娘背景可在设置中选择，支持保留或更换本地自定义背景；提供同一形象的 Q 版头像与 HDSL 图标。
- 从 [GitHub dsh-plugin 话题](https://github.com/topics/dsh-plugin) 搜索社区项目，按需核验仓库包声明与 npm 发布，确认安装目标后再安装。
- 优先从 [DeepSeek 官网](https://www.deepseek.com/en/download/)查询 Windows x64 桌面端，也可选择两个明确标注的社区来源；显示下载进度和摘要校验结果，下载完成后由用户运行安装。

## 使用

打开便携目录中的 `HDSL.exe`。请保留整个目录，不能只复制 exe。

1. 在设置中填写代理，例如 `http://127.0.0.1:7890`，并保存。
2. 打开“创建实例”，从自动刷新的列表选择精确版本，也可直接输入完整版本号；默认 profile 为 `web`。
3. 可在“账户”页保存 API Key 账户并绑定实例；保存只记录配置，不测试 API 连接或发起收费请求。也可继续在 Harness 工作台中配置。
4. 点击启动，首次使用会安装依赖；就绪后打开 Web 工作台。账户切换在下一次启动时生效。

数据默认写在 exe 所在目录。详细说明、数据位置和验收步骤见 [使用说明](docs/USER_GUIDE.md)。旧版使用说明保留在 [legacy/README-v0.1.md](docs/legacy/README-v0.1.md)。

在“设置 → 外观”选择晴空招手、浅海微风、暖阳小憩、月色晚安或初雪围巾，然后保存。默认使用晴空招手。新图标与五张背景由内置图像生成工具制作，素材、提示词和参考关系见 [图片来源记录](assets/artwork/README.md)。

## 兼容范围

- 新旧 CLI 通过能力检测适配；未来删除参数、改变 profile 模型或要求新的 Node 接口时，仍可能需要更新启动器或工具链。
- 预发布 Harness 版本按原版本号显示，不等同于稳定版本。
- 分享导出排除凭据、会话、私人设置和可能含密钥的补丁文件。导入或复制后需要重新配置这些内容；它不是完整备份。
- 外部插件和整合包安装脚本默认关闭，需要原生构建的插件可能需要额外处理。
- 未知整合包格式明确拒绝；完整格式矩阵见 [整合包支持](docs/pack-support.md)。
- 账户绑定使用临时启动配置适配已支持的 Harness 结构；未知结构会明确报错。独立桌面应用的账户与插件由其自身管理，HDSL 不自动向它们注入账户。
- 话题标签不代表项目可直接安装；未确认发布包的项目只提供查看入口。目录核验不等同于插件功能测试。

## 构建

需要 JDK 21 或更新版本。在 PowerShell 中运行：

```powershell
.\scripts\build.ps1
.\scripts\build.ps1 -Package
```

脚本按固定版本下载并校验 Maven；打包使用 `jpackage`，带 Java 运行环境、Node 和 pnpm，并附上对应源码与构建脚本：`sources/hdsl-0.2.0-source.zip`。也可以使用已安装的 Maven：

```powershell
mvn -B -ntp verify
java '-Dhdsl.data=.run-data' -jar target/hdsl-0.2.0.jar
```

源码运行时需在数据目录准备 `tools/node` 和 `tools/pnpm`，或通过 PATH 提供相应工具。联网安装和真实 UI 验证为独立测试，默认普通构建不运行。CI 只构建和测试，不自动发布。

## 项目说明

- [运行时和插件兼容](docs/runtime-compatibility.md)
- [账户与下载说明](docs/accounts-and-downloads.md)
- [整合包支持](docs/pack-support.md)
- [验证记录](docs/VALIDATION.md)
- [调研与来源](docs/research/2026-10-02-launcher-and-modpacks.md)
- [第三方声明](THIRD_PARTY_NOTICES.md)
- [HMCL 移植范围及来源](docs/HMCL_PORT.md)

新版代码位于 `src/main/java`，测试位于 `src/test/java`。原 Swing 源码 `src/com/hdsl/Launcher.java` 和原测试保留作迁移参考，不参与新版 Maven 构建。

本版包含 HMCL 派生代码，整体按 GPLv3 或更新版本及 HMCL 附加条款发布；原 HDSL 的 MIT 版权声明保留。移植范围和改动见上述来源文档，界面内保留上游版权说明。没有复用 Overture 源码。HDSL 与 DeepSeek、HMCL 无官方隶属关系。
