# HDSL：HMCL 界面与 DSH 整合包研究

研究日期：2026-10-02。对象：`jiefing/HDSL`。

> 实施决定更新（同日）：用户明确选择直接移植 HMCL 界面代码，并接受相应 GPL 发布要求。本文以下内容保留为早期调研记录，其中“独立实现”的推荐已被这一决定替代。实际移植范围、来源与许可见 [HMCL_PORT.md](../HMCL_PORT.md)，实际验证见 [VALIDATION.md](../VALIDATION.md)。

## 1. 结论

**可以把 HDSL 做成 HMCL 风格的桌面启动器，也可以支持 DSH 整合包。** 两项工作需要一起设计：界面负责实例选择、安装向导和任务进度；底层负责运行时、依赖、目录隔离和进程生命周期。

建议路线：**保留 Java 后端，先拆分现有单文件逻辑，再用 JavaFX 实现新的界面；整合包优先支持 PackForge `.dspack`，另设 Overture ZIP 导入适配器。**

这份文档是源码研究和实施建议。本次完成仓库克隆、许可证核对、格式与代码交叉检查；尚未改写启动器、运行外部整合包或完成互操作验收。

## 2. 已克隆仓库

| 项目 | 本地路径（相对工作区） | 固定提交 | 克隆方式 |
| --- | --- | --- | --- |
| HDSL | `.` | `bc519f1e7557018d8f9d810ada5d7670583b901e` | 完整克隆，main |
| HMCL | `_references/HMCL` | `77eee17d361996259a48cc7896006a57d2e34a2a` | 当前分支浅克隆，main |
| Overture | `_references/DSH-Melody-Launcher-Overture` | `9ad2980bf3ddaa91f46bf9bd09cf8ef98af697bc` | 当前分支浅克隆，main |
| PackForge 插件 | `_references/dsh-pack-plugin` | `7c87e8620d9097b7e18f96769af4b3edd98031db` | 当前分支浅克隆，master |
| PackForge 规范（补充） | `_references/DSH-PackForge` | `80ffa68da7a1d86e80892d994b65517e397783ba` | 当前分支浅克隆，main |

工作区：`G:\WORK\HDSL`。参考仓库保留各自 `.git`，已通过主仓库 `.gitignore` 排除，避免以后误提交为嵌套仓库。机器可读记录见同目录 `sources.json`。

补充规范仓库的原因：插件 README 的格式链接指向独立仓库，仅看插件说明不足以确定格式契约。以下结论以表中提交的本地源码为准，网页缓存与 README 可能不同步。

## 3. HDSL 当前基础

HDSL 版本文件为 `0.1`。主程序 `src/com/hdsl/Launcher.java` 共 1,938 行，使用 Swing，UI、设置、实例、插件、运行时安装和进程控制放在同一个类中。README 与 CI 指定 JDK 25。

已有能力可继续利用：

- 每实例独立 `instances/<id>/dsh-home`、工作目录、日志与端口。
- 通过 `DSH_HOME` 指定实例目录，使用 `--profile` 启动 profile。
- 按 DSH 版本管理 `runtimes/<version>`；内置工具优先，允许使用系统 npm。
- 插件缓存、后台刷新、进程状态检测和停止进程树。
- 已有布局、隔离、运行时和生命周期测试源码。

需要优先调整的地方：

1. 默认 DSH 版本仍为 `0.1.0-rc.6`；应建立版本发现与已测兼容列表，不能简单替换成未经验证的最新版。
2. 运行时实际安装走 `npm install --prefix`；整合包 profile 的 pnpm 依赖恢复应独立管理，不能直接复用这条安装命令。
3. 当前没有 `.dspack` / Overture 快照导入服务，也没有完整的安装事务与格式识别层。
4. `isolatedEnvironment()` 设置了 `DSH_HOME`，未显式设置 `DSH_AGENTS_HOME`。升级 DSH 时要验证共享 agent 配置对隔离的影响；包格式本身不包含用户全局 `~/.agents`。
5. 当前 JSON 辅助解析使用正则，不能用来解析嵌套 manifest。需要正式 JSON/YAML 解析器与明确的数据模型。

源码入口：[Launcher.java](../../src/com/hdsl/Launcher.java)。本次没有运行这些测试，也没有用用户真实实例做迁移测试。

## 4. HMCL 界面怎样使用

### 4.1 技术判断

HMCL 使用 JavaFX、JFoenix、CSS、主题与导航控件。HDSL 目前使用 Swing，两者不能通过替换一个主题文件直接互换。

HMCL 的 `RootPage` 包含账号、当前实例、实例管理等侧栏入口；`MainPage` 将主要启动控件放在右下方，并提供实例切换菜单；主页面还接入整合包拖入向导。这些交互适合转换为 DSH 实例管理。

| 路线 | 能达到的效果 | 成本与影响 |
| --- | --- | --- |
| 在 Swing 上重画布局 | 接近 HMCL 的布局和色彩 | 改动较小，但动画、控件和主题仍需自行实现 |
| **JavaFX 独立实现相似交互（建议）** | 侧栏、背景、卡片、启动按钮、向导与主题可统一实现 | 需要迁移 UI；业务服务和实例数据可延续 |
| 直接移植 HMCL 控件/界面代码 | 最接近其实际控件行为 | 需要裁剪 Minecraft 业务依赖，并按 GPLv3 与附加条款处理发布 |

推荐第二条。它保留 Java 技术路线，也避免将账号认证、游戏下载、ModLoader 等与 DSH 无关的逻辑带入。JavaFX 的具体版本和打包组合在开发阶段根据 JDK 与 Windows/Linux 构建结果确定。

### 4.2 页面设计建议

| HMCL 交互位置 | HDSL 对应内容 |
| --- | --- |
| 左侧身份/状态区域 | HDSL 品牌、运行状态、当前实例摘要 |
| 当前游戏版本 | 当前 DSH 实例、profile 和 DSH 版本 |
| 实例管理 | 实例列表、复制、重命名、备份、删除 |
| 下载/安装 | DSH 版本与整合包；显示来源、大小和兼容信息 |
| 主背景区域 | 用户背景、当前实例简介、最近任务 |
| 右下角启动按钮与切换入口 | 启动/停止、打开工作台、切换实例 |
| 整合包拖入向导 | 识别格式 → 安装预览 → 选择版本 → 安装进度 → 完成 |

建议侧栏：**启动、实例、整合包、运行时、插件与技能、任务与日志、设置**。安装任务离开页面仍可继续；失败应定位到下载、解压、安装依赖或启动阶段。

页面应区分三个概念：**整合包是可分发文件；实例是安装后的隔离环境；profile 是实例内部的一套加载配置。** 一个 dshhome 包可能包含多个 profile，不能把三者都压成一个名称字段。

### 4.3 许可证与素材

- HDSL：MIT。
- HMCL：GPLv3，并要求修改版以名称或版本区分原版、保留界面显示的版权声明。直接移植代码后不能把包含该代码的衍生发布仅标为 MIT；应保留相应版权、提供对应源码并满足分发要求。
- PackForge 插件及规范：MIT；若实际复用代码，应保留其许可与版权说明。
- Overture：本次提交未找到覆盖启动器代码的根 LICENSE，`package.json` 也未声明 license。仓库中的 `humanizer-zh/LICENSE` 只属于那个技能，不能作为整个启动器的授权。可以研究结构与行为；复制其实现或素材前需确认授权。

独立 UI 方案使用 HDSL 自己的品牌和素材；任何拟复用的 HMCL 图标、背景或 CSS 需单独记录来源与授权。以上是本次工程路线的许可证依据，不代表已完成未来发布物的逐文件审查。

## 5. 整合包并不是一种通用 ZIP

| 格式 | 识别方式 | 核心内容 | HDSL 建议 |
| --- | --- | --- | --- |
| PackForge 当前格式 | 根 `dspack.json` 与 `manifest.json` | profile 或 dshhome、依赖清单、覆盖文件、下载资源 | 第一优先，导入与导出 |
| PackForge 历史格式 | 旧容器标记或旧 manifest | 不同历史契约 | 单独版本适配，暂不宣称全兼容 |
| Overture 快照 ZIP | `dsh-snapshot.json`、`profiles/<id>/profile.yaml` 等 | DSH_HOME 文件、已安装依赖、链接元数据 | 独立导入器，优先 Windows 同平台 |
| Overture 清单模式 | `dsh-pack.yaml` | 插件、技能、预设、应用及版本信息 | 另一个适配器，不与快照解析混用 |
| 普通 ZIP | 没有符合契约的标记 | 未知 | 显示无法识别，不直接当作实例安装 |

后缀只用于文件筛选，内容决定格式。多个标记冲突时应报格式歧义，不能按匹配顺序悄悄安装。

## 6. PackForge `.dspack` 格式

### 6.1 容器与清单

容器是标准 ZIP，无专用二进制头。当前规范要求根文件：

```json
{"format":"dspack","version":3}
```

上面是 `dspack.json`。另一个根文件 `manifest.json` 的 `manifestVersion` 是 `5`。**容器版本 3、manifest 版本 5、市场索引版本 2 是三套独立版本。**

manifest 的核心字段：

- 通用：`manifestVersion`、`type`、`name`、`version`。
- 可选展示及环境信息：`displayName`、`description`、`author`、`icon`、`dshVersion`、`dshVersions`、`launchers`。
- profile：有序 `bundles[]`、依赖坐标到版本的 `dependencies{}`，以及可选 `profileName` 和 `patch`。
- dshhome：`profiles{}`、`defaultProfile`，以及可选 `presets`、`skills`、`instructions`。
- 外部资源：`files[]`，每项包含相对落点、`sha256`、`size` 和 `urls[]`。重技能还可能通过 `skills[]` 提供下载指针。

`bundles` 代表实际加载顺序，`dependencies` 代表安装依赖，二者不能互相替代。根 `package.json` 是快照，安装时应根据 manifest 重建相关字段，再做依赖与 bundle 对账。

### 6.2 两种落盘方式

| 包内位置 | profile 形态落点 | dshhome 形态落点 |
| --- | --- | --- |
| `overrides/` | `$DSH_HOME/profiles/<profile>/` | `$DSH_HOME/` |
| `home/` | `$DSH_HOME/`（可选） | 当前该形态无需此层 |
| 根机器文件 | 供 profile 依赖恢复使用 | 按规范及各 profile 处理 |

profile 包中的 home 内容通常是 skills、agent presets 等。dshhome 中 `profiles` 必须非空，`defaultProfile` 必须指向存在的 profile；该规范排除 `web` / `headless` 基线模板。不得把 dshhome 的 `overrides/` 再套入单一 profile。

### 6.3 依赖、内嵌包与离线能力

当前规范快照的常规路径是：恢复文件 → pnpm 重建依赖 → 对账。插件代码另外实现了 `vendored{}` + `vendor/*.tgz`：条目包含 `version`、`sha256`、`size`、`path`，可带 `reason`。

应在安装前核对 tarball 的大小、哈希与依赖坐标，再进入依赖恢复。`vendor:` 方言、`file:` 引用和 registry 包不能统一按 npm 包名下载。

**本次发现的文档/实现差异：**

1. 插件 README 与代码描述 r2/vendor 能力，但克隆的规范 `manifest/v5.md` 与 `pack-structure/v3.md` 没有相应 vendored 定义。应暂按“已观察到的实现扩展”建立兼容测试，不能宣称所有同版本工具均支持。
2. 插件 `install.js` 在含传递闭包条目时明确保守使用 `--prefer-offline`，说明 store 预填充尚未实现。包带齐 tarball 并不等于安装完全不联网。
3. 规范要求校验根标记与容器版本；插件 `parseDspack()` 允许缺标记，`installPack()` 未使用返回的 marker 做容器版本门禁。HDSL 应按明确的格式识别规则处理，不能直接继承这种宽松行为。
4. 插件校验器只接受 manifest 4/5，明确拒绝 2/3；规范仓库的集成指南则讨论更早格式。支持范围必须以本启动器通过验收的适配器为准。
5. `launchers` 的提示规则是警告后可继续。插件会直接记录“用户已确认继续”的日志；HDSL 界面需要真实呈现提示并接收选择，不能把日志文本当作用户确认。

### 6.4 版本选择与市场

DSH 版本优先选择已安装的 `dshVersion`，其次是 `dshVersions` 与已安装版本交集中的最新兼容版本；缺失时引导安装声明版本。导出尽量锁定实测版本。启动器版本比较规则与 DSH semver 规则分开实现。

市场索引为 `schemaVersion: 2`，列表在 `modpacks[]`。安装所需三项是 `downloadUrl`、`sha256`、`size`；详情从 `packs/<owner>.<repo>/manifest.json` 和 README 按需读取。插件默认索引地址：

`https://dsh-packforge.github.io/dsh-pack-market/index.json`

这是从代码确认的地址，本次未对市场中的发布资产逐一下载验证。

**命名冲突：**规范的启动器注册表中，`hdsl` 链接到 `https://github.com/MCXCC303/HDSL`，不是本项目。实现时不得把那个 ID 的兼容声明直接当成本项目的测试结论。建议先使用可配置的项目专属内部 ID，正式对外登记前再确认名称；本次未联系上游或提交注册 PR。

## 7. Overture 格式与可参考设计

Overture 使用 Electron + React + TypeScript，本次 `package.json` 为 `1.0.2`。

### 7.1 快照 ZIP

关键实现位于 `electron/pack-snapshot.ts`：

- 根元数据文件为 `dsh-snapshot.json`，当前 `format` 为 `1`。
- 元数据记录 `platform`、`exportedAt`、`links`、`excluded`、`warnings`，以及可选隐私类别声明。
- 包内容以 DSH_HOME 为根，可包含 profile 的 `node_modules`。DSH 版本从 `profiles/<id>/profile.yaml` 读取。
- 符号链接在元数据中记录，解压后重建；还需处理 profile 改名、绝对路径和本地插件本体。
- 实现提供流式 ZIP、阶段进度和容量限制；快照提取本身不做联网安装，但启动器其他步骤可能补装缺失的 DSH 运行时。

HDSL 导入时应检查平台与本机运行时。原生依赖可能受 CPU 架构、Node ABI 和系统影响，元数据中的 `platform` 并不足以保证二进制兼容。应明确显示兼容性未知的情形，不能宣传“任意机器解压即用”。

快照中的链接必须验证链接路径和最终目标都处于新实例内，包括 Windows junction、UNC 和盘符路径。不能因来源是参考项目就省略自己的边界检查。

### 7.2 另一条清单路径

`electron/pack-manifest.ts` 还定义 `dsh-pack.yaml`。解析器要求名称、描述、包版本和 `plugins[]`，并支持 `dshVersion`、`presets`、`skills`、`applications` 等字段。这不是 PackForge 的 JSON manifest，也不是 `dsh-snapshot.json`。

### 7.3 适合借鉴的行为

- 一个包对应独立环境；更换 DSH 版本先复制实例，原实例可以继续使用。
- 安装时分开显示下载与解压/恢复进度，下载 100% 不等于安装完成。
- 用户可见的代理与下载来源；超时、断流、失败原因记录到任务日志。
- 导入失败回滚，删除使用可恢复的目录操作；运行中实例先处理进程状态。
- 分享型导出默认排除凭据、会话和机器状态；“本机备份”与“可分享整合包”在 UI 中分开。

这些是待独立实现的产品行为参考，不表示本次已经复制其代码或验证所有宣传能力。

## 8. 建议的 HDSL 实现结构

先把 `Launcher.java` 拆为以下职责，再接 JavaFX：

- `InstanceService`：实例清单、端口、profile、迁移和状态。
- `RuntimeService`：DSH/Node/pnpm 版本、安装与校验。
- `ProcessService`：进程启动、就绪检测、停止与日志。
- `DownloadService`：代理、超时、续传、大小与 SHA-256。
- `PackService`：识别、检查、安装计划、导入、导出、回滚。
- 格式适配器：PackForge profile、PackForge dshhome、Overture snapshot、Overture YAML。
- `TaskService`：阶段进度、取消、错误和任务历史。

PackForge 的 core 通过 Host 接口隔离文件系统、下载和进程调用，可用于参考服务边界。若复用 MIT core，必须固定版本、保留许可并通过受控 Node 子进程暴露有限操作；不能简单把依赖 DSH 桌面端 `ctx` 服务的整个插件当作 Java 库加载。

### 实例布局与事务

```text
instances/<id>/
  instance.json              # 新元数据：格式来源、包版本、DSH 版本、默认 profile
  dsh-home/
    profiles/<profile>/
    skills/
    .agent-presets/
  workspace/
  logs/
cache/pack-staging/<job-id>/  # 安装暂存区
```

这是建议的新结构。旧 `config/instances.properties` 应由可回滚迁移导入，保留旧目录、端口、名称与自定义命令；不能直接要求用户删除重装。

一次导入创建一个新实例：profile 包落到它的 `dsh-home/profiles/<name>`；dshhome 包整体落到它的 `dsh-home`，保留多个 profile；Overture 快照通过独立映射恢复。`home/` 内容也只写入新实例，不写用户全局 DSH_HOME。

安装顺序：**检查归档 → 展示安装计划 → 确认兼容提示 → 暂存下载/解压 → 校验与依赖恢复 → 校验启动配置 → 发布实例目录及清单 → 可选启动。** 失败或取消只能清理本次任务的暂存目录，不影响已有实例。

安装前展示目标目录、格式、包和 DSH 版本、下载量、预计占用及需要的依赖脚本行为。ZIP 路径穿越、路径大小写冲突、重复条目、解压体积上限、资源哈希、链接逃逸与依赖脚本属于导入器本身的验收内容。

## 9. 实施顺序与验收

| 阶段 | 交付 | 验收重点 |
| --- | --- | --- |
| 1. 后端整理 | 模块拆分、统一构建、旧配置迁移、版本与代理设置 | 旧实例仍可启动；端口与进程所有权行为保留 |
| 2. JavaFX 界面 | HMCL 风格导航、背景、实例选择、启动和任务页 | 缩放、中文长名称、窗口尺寸、后台任务不卡 UI |
| 3. `.dspack` 基础 | v3 容器 + v5 profile/dshhome 导入导出 | 两种落点、默认 profile、依赖与加载顺序、校验失败回滚 |
| 4. 实现扩展与市场 | vendored、锁文件、v4 兼容、市场详情和下载 | 真正断网测试离线包；部分内嵌包明确需要网络 |
| 5. Overture 适配 | 快照导入，再考虑 YAML 清单 | Windows 中文路径、链接、profile 改名、缺运行时及跨平台提示 |
| 6. 发布 | Windows 可用产物、迁移说明、许可证说明 | 真实整合包安装—启动—导出—再次导入；Linux 单独验收 |

正式标记“支持整合包”前，至少验证：两种 PackForge 形态各一份真实包；一份带 vendor 的包；一份 Overture 快照；篡改哈希与越界路径的失败样本；中途取消；两个实例互不污染。测试使用临时实例与合成数据，不需要读取用户的 API 凭据。

本次只做研究，没有安装依赖、构建或运行三方启动器，也没有发布 GitHub 提交、PR 或 Release。

## 10. 源码依据

以下链接固定到本次克隆提交，便于复查：

- [HDSL 主程序](https://github.com/jiefing/HDSL/blob/bc519f1e7557018d8f9d810ada5d7670583b901e/src/com/hdsl/Launcher.java)：Swing、实例布局、运行时安装和环境。
- [HMCL RootPage](https://github.com/HMCL-dev/HMCL/blob/77eee17d361996259a48cc7896006a57d2e34a2a/HMCL/src/main/java/org/jackhuang/hmcl/ui/main/RootPage.java)、[MainPage](https://github.com/HMCL-dev/HMCL/blob/77eee17d361996259a48cc7896006a57d2e34a2a/HMCL/src/main/java/org/jackhuang/hmcl/ui/main/MainPage.java)、[许可说明](https://github.com/HMCL-dev/HMCL/blob/77eee17d361996259a48cc7896006a57d2e34a2a/docs/README_zh_Hans.md)。
- [PackForge manifest v5](https://github.com/DSH-PackForge/DSH-PackForge/blob/80ffa68da7a1d86e80892d994b65517e397783ba/specs/manifest/v5.md)、[容器 v3](https://github.com/DSH-PackForge/DSH-PackForge/blob/80ffa68da7a1d86e80892d994b65517e397783ba/specs/pack-structure/v3.md)、[市场索引](https://github.com/DSH-PackForge/DSH-PackForge/blob/80ffa68da7a1d86e80892d994b65517e397783ba/specs/index/index.md)、[启动器注册表](https://github.com/DSH-PackForge/DSH-PackForge/blob/80ffa68da7a1d86e80892d994b65517e397783ba/specs/launcher-registry.md)。
- [插件容器解析](https://github.com/DSH-PackForge/dsh-pack-plugin/blob/7c87e8620d9097b7e18f96769af4b3edd98031db/src/core/dspack.js)、[manifest 校验](https://github.com/DSH-PackForge/dsh-pack-plugin/blob/7c87e8620d9097b7e18f96769af4b3edd98031db/src/core/manifest.js)、[安装器](https://github.com/DSH-PackForge/dsh-pack-plugin/blob/7c87e8620d9097b7e18f96769af4b3edd98031db/src/core/install.js)、[内嵌依赖](https://github.com/DSH-PackForge/dsh-pack-plugin/blob/7c87e8620d9097b7e18f96769af4b3edd98031db/src/core/vendored.js)。
- [Overture 快照](https://github.com/Miyazawai/DSH-Melody-Launcher-Overture/blob/9ad2980bf3ddaa91f46bf9bd09cf8ef98af697bc/electron/pack-snapshot.ts)、[YAML 清单](https://github.com/Miyazawai/DSH-Melody-Launcher-Overture/blob/9ad2980bf3ddaa91f46bf9bd09cf8ef98af697bc/electron/pack-manifest.ts)、[导入编排](https://github.com/Miyazawai/DSH-Melody-Launcher-Overture/blob/9ad2980bf3ddaa91f46bf9bd09cf8ef98af697bc/electron/pack.ts)。
