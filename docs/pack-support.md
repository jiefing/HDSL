# 整合包支持与边界

本页描述新版 HDSL 中实际实现的格式适配器。依据为 `docs/research/2026-10-02-launcher-and-modpacks.md` 固定的上游源码和格式规范；没有复用 Overture 或 HMCL 的实现代码。

## 当前支持

| 格式 | 导入 | 导出 | 约束 |
| --- | --- | --- | --- |
| PackForge 容器 v2 + manifest v4 | 单 profile | 统一导出 v3/v5 | v2 不接受 `home/` |
| PackForge 容器 v3 + manifest v4/v5 | 单 profile | v3/v5 | `overrides/` 落到 profile；`home/` 落到 DSH_HOME |
| PackForge 容器 v3 + manifest v5 | 多 profile 的 dshhome | v3/v5 | 保留默认 profile；不含 `web`、`headless` 基线模板 |
| PackForge `vendored{}` 扩展 | 标准 `.tgz` 条目 | 导出为包内 `file:vendor-blobs/…` 依赖 | 大小与 SHA-256 预验；需要 pnpm 恢复依赖 |
| Overture `dsh-snapshot.json` format 1 | 同平台快照 | 可转换为 PackForge 分享包 | 需要 profile.yaml；内置依赖可能受 Node ABI 和 CPU 架构影响 |

未知容器或 manifest 版本明确拒绝，避免把未来新格式错误解释为旧格式。已支持格式内的未知可选元数据保存在实例的 `.hdsl-pack-origin.json`。重建 `package.json` 时保留未知字段，仅以 manifest 覆盖依赖及有序 `dsh.profile.bundles`。不会按照依赖名称重新排序 bundle。

**`desktop` profile 的启动限制：** 部分新版 Harness CLI 将该名称保留给 Electron 桌面端。HDSL 在预览中对此给出明确提示，仍允许导入文件和恢复依赖，并保留原 profile 名称；不会自动改名规避上游限制。能否启动需要针对所用 Harness 适配和验证，不能把解包、依赖恢复通过表述为该整合包已能原样启动。

### 暂未支持

- 旧 `.tgz` 整合包、无格式标记 ZIP、manifest v2/v3。
- Overture 的 `dsh-pack.yaml` 清单模式。
- DSHL 的 `vendor:` 方言、`vendor/vendor.json` 直挂模式，以及 `workspace:` / `link:` PackForge 依赖。
- 跨平台快照、快照中的外部绝对链接、缺少链接目标、嵌套链接、间接链接或潜在目录循环。
- 自动执行依赖安装脚本和声明式扩展中的任意安装钩子。
- 完整本地备份及恢复凭据、会话的流程。

这些情况在检查阶段给出明确错误。未知可选字段的保留不等于实现了该字段描述的新功能。

## 分享导出与隐私

**当前导出是分享包，不是完整备份。**

导出按路径跳过已知凭据文件、`.env`、SSH 和云凭据目录、`.npmrc`、私人设置、会话、附件、缓存、日志和 `node_modules`。**不会先打开真实凭据再尝试脱敏。** 因为 `cordis.patch.yml` / `.yaml` 也可能内嵌密钥，分享导出保守地排除整个补丁配置。manifest 的 `patch` 字段也不导出。

因此，导入分享包后需要重新设置提供商、API 凭据及部分插件选项。导出的 `hdslShareExport` 元数据和检查界面的警告会说明这些省略项。测试中的“往返”指依赖、bundle 顺序、可分享资源、未知非配置元数据的往返，不包括补丁和私人配置。

路径排除不能识别用户在任意其他文档、源码或自定义字段中自行写入的秘密。分享前仍应查看导出清单；本实现没有读取所有文件内容并扫描秘密。API 不会覆盖已有导出文件，也不允许把输出包写进源 DSH_HOME，以免递归打包或修改源文件。

## 导入流程

1. 读取 ZIP 索引与少量格式元数据，确定格式和兼容范围。
2. 校验路径、容量、profile、依赖及资源指针；对内嵌 tarball 预验 SHA-256 和大小。
3. 记录整个归档的 SHA-256，将用户检查的内容绑定到后续解压操作。
4. 只写入新的空暂存目录，保留原实例。重复检查归档，拒绝检查后更换的文件。
5. 根据 profile/home 映射恢复文件，依据 manifest 重建机器文件，再下载并验证资源。
6. PackForge 由上层运行时服务恢复依赖。快照由适配器重建验证过的内部链接。
7. 上层实例服务完成依赖和运行时验证后发布实例；失败负责清理本次暂存目录。

`PackService.extract` 不执行 npm、pnpm、插件代码或 Harness；调用方必须管理暂存事务。`PackService(URI proxy)` 支持启动器配置的 HTTP 代理，默认构造器使用 Java HTTP 客户端的默认代理行为。

### 依赖和外部资源

- `files[]` 与重型 `skills[]` 指针仅接受不含内嵌用户凭据的 HTTPS 地址，按顺序尝试镜像。
- 每个资源要求合法相对路径、大小与 SHA-256；禁止覆盖归档文件或 profile 的机器文件。
- HTTP 响应和下载体积均受限，每次响应体有 5 分钟期限，最多跟随 5 次 HTTPS 重定向。
- `vendored` 直接依赖恢复为 `file:vendor-blobs/<sha256>.tgz`。闭包条目可按锁文件登记并校验，但本实现没有将传递依赖预填充到 pnpm store。
- **内嵌 tarball 不代表完全离线。** 未内嵌的依赖、git 依赖和传递依赖可能仍需联网。
- 本地 `file:` 引用必须位于包内 profile 且有对应内容。快照仍引用源机器的路径时拒绝导入。
- 导出遇到版本范围时，尽量从 profile 内已安装的包清单锁定精确版本；无法确认时拒绝导出，不把未实测范围写成精确版本。

## 路径和容量检查

- ZIP 最多 2 GiB；展开及计划恢复内容最多 6 GiB；单文件最多 2 GiB；最多 400,000 条目。
- 大于 1 MiB 且压缩比超过 1,000 的条目拒绝；元数据读取最多 4 MiB。
- 拒绝 `..`、绝对路径、盘符、UNC、反斜杠、NTFS 流、Windows 设备名、尾部点或空格。
- 拒绝重复路径、Unicode/大小写碰撞、文件与目录冲突，以及映射后目标冲突。
- 检查 ZIP 实际展开大小与 CRC。ZIP 内直接声明的 symlink 和特殊文件拒绝。
- 内嵌与包内 `file:` npm tarball 还会流式检查内部 `package/` 路径、重复项、链接、特殊文件、PAX/GNU 元数据大小及展开上限；哈希正确不能替代此项检查。
- 目标根和所有现有父目录不得包含 symlink、Windows junction 或其他重解析点。
- 快照链接只有在目标存在于同一新实例、没有外部引用或循环风险时才会创建。Windows 缺少符号链接创建权限时，错误会要求启用开发者模式后重试；不会静默丢失链接。

## 验证

`src/test/java/com/hdsl/pack/PackServiceTest.java` 使用临时目录与合成内容，不读取真实 API 密钥。覆盖旧/新 profile、home、快照格式识别，中文路径，默认 profile，未知字段及有序 bundle，vendor 大小/哈希篡改，路径穿越和碰撞，ZIP symlink 与压缩率限制，输出覆盖保护，检查后换包，分享导出及再次导入。

本机的自动化测试通过不代表已验证市场上所有真实整合包、Windows 所有链接权限模式或完全断网安装。外部 HTTPS 资源的真实镜像可用性、依赖安装及 Harness 启动须由集成验收另行记录。上游链接和版本声明也不构成对未来 Harness 或插件版本的兼容保证。

### 真实市场文件验证（2026-10-02）

通过配置的 `127.0.0.1:7890` 代理读取[官方市场索引](https://dsh-packforge.github.io/dsh-pack-market/index.json)，按索引的大小和 SHA-256 校验下载文件后，执行 `PackService.inspect` 和 `extract`。

| 真实文件 | 字节数 | 声明 Harness | 结果 |
| --- | ---: | --- | --- |
| [pokemon 1.0.0](https://github.com/hxh230802/pokemon/releases/download/v1.0.0/pokemon-1.0.0.dspack) | 2,574 | `0.1.5-alpha.2` | v3/v5 单 profile 检查、解压、bundle 顺序与依赖数量验证通过 |
| [desktop-pack 1.0.0](https://github.com/1900992335/desktop-pack/releases/download/v1.0.0/desktop-pack-1.0.0.dspack) | 3,967 | `0.2.0-rc.2` | v3/v5 单 profile 检查、解压、bundle 顺序与依赖数量验证通过 |

SHA-256：

```text
pokemon:      3d6a2748f424ecebca2c9dd0ea108a91eaa729fd4ba40cceedaa304d9165087f
desktop-pack: cc4c0a1650649c8bd37edc2587b081f8e28a08f2ade78d39a26e14fccd0373e1
market-index: 964d00058451f7b53317e309797f3bab9aa1b5c986d4f195824956c2a20b0479
```

发现并处理的实际差异：

- pokemon 包含本应属于源机器的 `.dshpkcfg` 导出工作区配置。过滤器增加该文件及 `.dsh-pack` 状态目录；通过路径排除，没有读取这份配置内容。
- desktop-pack 的 `@dsh-packforge/dsh-pack-plugin` manifest 依赖声明与附带 lockfile 的 importer specifier 不同。按 manifest 重建后，严格 frozen 安装可能触发 `ERR_PNPM_OUTDATED_LOCKFILE`；上层暂存导入允许针对该错误备份旧锁文件并刷新，其余安装错误仍失败。

原始索引、下载文件和带来源条目/实际哈希/解压目录的报告保留在 `.test-data/real-packs/`，其中 `extraction-report.json` 为机器可读报告。该目录已被 Git 排除。可用以下可选测试重复检查已下载的文件：

```powershell
mvn -Dhdsl.realPackFixtures=true -Dtest=com.hdsl.pack.PackRealFixtureTest test
```

普通测试不启用此项，不依赖联网或真实用户实例。上述 `PackRealFixtureTest` 阶段没有安装依赖、执行插件代码或启动 Harness，也不代表多 profile 真包、带 vendor 真包或 Overture 真快照已通过端到端运行验收。

### desktop-pack 的真实依赖恢复（2026-10-02）

随后在新的隔离目录中，对同一份已核对 SHA-256 的 desktop-pack 运行真实 `RuntimeService.restoreProfile(..., true)`，使用准备好的 Node、pnpm 10.34.0、官方 npm Registry 和 7890 代理。

- 第一遍冻结安装真实返回 `ERR_PNPM_OUTDATED_LOCKFILE`。
- 仅针对该错误创建 `.hdsl-original-pnpm-lock.yaml`，其 SHA-256 与恢复前的原锁文件相同。
- 第二遍更新锁文件并完成安装：pnpm 报告添加 12 个包，其中复用 4 个、下载 8 个。
- 4 个直接依赖的实际版本与 manifest 完全一致，最终目录中的 `node_modules` 链接可解析到 `package.json`，声明的 bundle patch 文件均存在。
- 两次安装均禁用依赖脚本和 pnpmfile 钩子；验证仅读取包元数据和检查文件存在，没有导入插件 JavaScript 或启动 Harness。

| 直接依赖 | 实际版本 |
| --- | --- |
| `@dsh-packforge/dsh-pack-plugin` | `0.3.5` |
| `dsh-context` | `0.61.0` |
| `dsh-plugin-marketplace` | `0.4.0` |
| `dsh-plugin-model-proxy` | `0.1.6` |

该次验证目录为 `.test-data/real-pack-install-3f57f80b/`，保留 `dependency-report.json`、`restore.log` 和最终实例目录；目录受 Git 排除规则保护。可选复现测试：

```powershell
mvn -Dhdsl.packDependencyIntegration=true -Dtest=com.hdsl.pack.PackDependencyIntegrationTest test
```

此结果证明该真实样本的文件导入和依赖恢复通过；插件实际行为、提供商连接、原生扩展功能及该整合包启动仍未在此测试中验证。尤其是本样本使用 `desktop` profile，受到部分新版 CLI 的 Electron 专用限制；当前没有证据表明 HDSL 能将它原样启动。
