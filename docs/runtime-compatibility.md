# Harness 与插件兼容性

## 设计原则

启动器不按版本号推断 CLI 参数，也不把可下载版本当作已验证版本。每个 Harness 精确版本单独安装在 `runtimes/<version>`，`pnpm-lock.yaml` 保存这次安装得到的完整依赖树。新版本安装成功后才移动到正式目录；失败保留诊断目录，其他版本不变。旧启动器通过 npm 安装的已有运行时继续识别，原 `package-lock.json` 保留。

`@deepseek-ai/dsh` 的 npm 清单提供版本列表；版本按 SemVer 排序，`rc.10` 高于 `rc.2`，同版本正式版高于预发布版。选择预发布版仍需用户判断。启动时从包内 `package.json` 的 `bin.dsh` 查找入口，不固定 `lib/bin.js`，也不依赖 Windows `.cmd` 的引号规则。

Node/pnpm 优先使用数据目录的 `tools`，其次使用打包程序旁的 `tools`，最后查找系统安装。因此通过 `HDSL_PORTABLE_ROOT` 或 `-Dhdsl.data` 指定外部数据目录时，仍能使用发布包附带的工具；运行时、实例与缓存继续写入所选数据目录。

运行时安装使用 pnpm，并显式打开 peer 依赖安装、使用 hoisted 布局。实测旧版包触发 npm peer 依赖循环解析，高 CPU 且内存持续上涨；上游也有同类报告。不能用 `--legacy-peer-deps` 简单跳过，因为那会漏掉启动所需的包。运行时官方包按正常包管理流程允许安装脚本；导入整合包和安装外部插件的脚本策略见下文。

还会使用 pnpm 的 `resolutionMode: time-based`。旧版 Harness 依赖的 Cordis HMR 等基础包后来发生行为变化，即使 DSH 包名固定，单纯解析到最新满足范围的依赖仍可能启动失败。按所选 Harness 发布时间选择历史依赖可恢复原发行组合，再由锁文件固定结果。更新这些历史依赖应通过选择新的 Harness 运行时完成。

旧发行版内部使用 `^0.1.0-rc.6` 等范围时，包管理器可能混装更新的 rc.8 组件。测试实际观察到 rc.6 主程序与 rc.8 的 base/web-app 混装。安装前会递归检查该发行版 `dependencies`、`peerDependencies` 和 `optionalDependencies`，仅将明确声明为同一个发行版本（完整版本号及其 `^`/`~` 形式）的 `@deepseek-ai/dsh-*` 包固定为该版，写入 `pnpm.overrides`。包名来自上游元数据；不同发行版本的依赖和第三方库继续遵循原声明。

## 首次安装进度与重试

发行依赖核对并行读取公开 npm 元数据，每批完成或等待约 2 秒时报告已核对数和当前待核对数。递归查询可能发现更多依赖，因此待核对数会变化；这些计数不转换成百分比。安装阶段使用固定的 `[安装进度] ` 前缀，依次说明清单核对、依赖下载与安装、入口校验和完成。pnpm 的原始解析、复用、下载与添加计数继续写入日志。

完整核对后的发行清单保存在 `cache/registry-metadata/<源地址SHA256>/cohort-<精确版本>.json`，有效期 30 天。它只包含公开包名、精确版本和校验信息。使用前校验缓存格式、完整标记、来源地址、版本、时间、数量、包名及内容 SHA256；过期或损坏时重新查询。源地址不同的清单分开保存。只有整个依赖图成功核对后才原子写入缓存，失败或取消的部分结果不会被当作完整清单使用。缓存不能保证上游包永远可下载，也不能替代实际安装与启动检查。

取消后保留诊断安装目录和 `cache/pnpm-store`。再次安装仍检查发行清单及运行时入口，并由 pnpm 复用已下载的包；不会将上次未完成目录直接登记成已安装运行时。已经成功安装的版本保持不变。

2026-10-02 的首次预览安装日志显示：15:36:38 开始核对，15:36:53 完成 277 项核对（15 秒）；随后 pnpm 用约 10 秒解析到 600 项，15:37:04 起持续报告下载与添加进度；15:37:38 用户取消时已有 528 个包下载并添加。问题是阶段状态不够清楚，日志未显示进程卡死。修复后使用同一预览目录正常重试，复用 528 个缓存包、新下载 2 个包，pnpm 安装阶段 37.3 秒；安装、启动、HTTP 200 检查和停止合计 58 秒。实例 `001` 的元数据保留，工作台实际可打开，停止后端口释放。

## 新旧 CLI 的处理

1. 执行实际安装版本的 `--help`，解析明确声明的参数和 plugin 命令。
2. 在一次性目录执行 `--profile web --help`，单独确认 Web 应用参数。新版 Harness 把 `--port` 等参数交给应用；它们不一定出现在顶层帮助中。
3. 只有确认支持 `--port` 后才生成实例启动命令；确认支持 `--host` 时将地址限制为 `127.0.0.1`。
4. 自定义 profile 单独探测。只复制依赖坐标及组合包名单，链接已安装的 `node_modules`。用户 patch、凭据、会话数据均不复制进探测目录。
5. 参数无法确认时给出可操作的错误，而不是默默启动到错误端口。帮助探测有超时，超时只停止本次启动的进程树。

Web 就绪检测使用 HTTP/1.1；旧服务对 Java 默认 HTTP/2 升级请求可能断开连接。新版服务启用本机访问 token，未带 token 的首页可返回 401。启动早期 fallback 尚未挂载时还可能短暂返回 404，不能仅凭该状态判定工作台可用。程序可在内存中使用自身进程公布的启动 URL，日志必须脱敏；兼容层不读取凭据文件。

所有探测都显式设置临时 `DSH_HOME` 和 `DSH_AGENTS_HOME`。运行实例也设置这两个目录。应用不会为检测读取系统 `~/.dsh`、`~/.agents` 或任何登录文件。

## 启动器长期不更新时

兼容层会保留并识别未来帮助中明确声明的新参数。插件安装交给当前实例所用的 Harness 和 pnpm；HDSL 不内置一个过时的插件清单，也不重新序列化和裁剪未知插件字段。

如果未来 CLI 的帮助格式变化，但已经人工确认参数仍有效，可新建 `config/compatibility.json`：

```json
{
  "versions": {
    "0.2.0-rc.2": {
      "launcherOptions": ["--profile"],
      "appOptions": ["--port", "--host"],
      "pluginManagement": true,
      "profiles": {
        "custom-web": {
          "appOptions": ["--port", "--host"]
        }
      }
    }
  }
}
```

这些声明必须来自本机维护者；不会自动下载、执行或信任远程兼容性脚本。配置仅声明固定能力，不接受 shell 命令。修改文件后下次检查会重新探测；`RuntimeService.refresh(version)` 也可显式清除会话缓存。

这不能保证任意未来破坏性变更都兼容。比如 Harness 改掉 profile 模型、删除端口参数、插件依赖新的 Node 原生接口，都可能需要更新启动器、Node 或插件。应用帮助不支持 `--help` 的自定义 profile 需要人工声明。依赖组合能否正常工作仍以实际启动验证为准。

## 插件与 Windows

插件列表只读取 profile 的 `package.json` 和安装包元数据，未初始化的 profile 不会因查看列表而创建。组合包名单中的未知对象会尽可能显示其 `name`/`package`；原始字段不被 HDSL 修改。

旧版 `0.1.0-rc.6` 的插件转发在 Windows 使用 `shell:true`。HDSL 用固定的本机 Node 桥接脚本载入原生 `dsh plugin add/update/remove`，只把它对子进程 pnpm 的 `spawn`/`spawnSync` 调用转换为 `node pnpm.cjs` 的直接参数数组并关闭 shell。新版 CLI 暴露 `runCli` 时，使用其安装器拥有的 `packageManager` 参数提供 Node 与 pnpm 入口。原生管理器仍能读取操作前后的依赖状态，正确移除组合包；包坐标即使有空格，也不会变成 shell 命令。未来 CLI 如果改用无法安全识别的 shell 命令，桥接层明确拒绝执行并提示需要更新适配。

`add` 支持 npm 坐标、GitHub/Git 坐标，以及相对工作目录的 `file:`/`link:` 本地包。HDSL 不接受以 `-` 开头的包坐标作为额外 pnpm 参数。

插件和整合包依赖恢复均关闭安装脚本及 `.pnpmfile.cjs` 钩子。pnpm 自身版本不会因包内 `packageManager` 字段自动切换。需要原生编译或构建脚本的插件可能还需用户按 pnpm 提示逐项允许构建；不会把“依赖已恢复”描述为“所有插件均可用”。

## 整合包依赖恢复

`RuntimeService.restoreProfile` 使用 pnpm；若已有 `pnpm-lock.yaml`，先传入 `--frozen-lockfile`。普通调用遇到锁文件和 package.json 不一致时明确失败。对于尚未发布的导入实例，调用方可显式设置 `allowLockRefresh=true`：仅当 pnpm 返回 `ERR_PNPM_OUTDATED_LOCKFILE` 时，将原锁保留为 `.hdsl-original-pnpm-lock.yaml`，记录原因，再根据已核对的包清单更新锁文件。安装脚本与 pnpmfile 钩子关闭，store 指向启动器自己的 cache。

## 测试方式

2026-10-02 在 Windows、Node.js 24.21.0、pnpm 10.34.0 下完成验证：

| Harness | CLI 能力检测 | 实际 Web 启动 | 原生插件安装/移除 |
| --- | --- | --- | --- |
| 0.1.0-rc.6 | 通过 | 裸首页 HTTP 200；Controller 启停/重启通过 | 本地 fixture 包通过，路径含空格 |
| 0.2.0-rc.2 | 通过 | 裸首页 HTTP 401；Controller 使用内存启动链接后 HTTP 200，启停/重启通过 | 本地 fixture 包通过，组合包对账正确 |

运行时模块当前 24 项单元测试通过；缓存与进度修复后的普通测试中，联网集成测试按默认设置跳过。此前 1 项覆盖两个真实发行版的联网集成测试通过；本次另对实际预览目录的新版安装、启动、工作台访问和停止完成验证。真实市场整合包的依赖恢复验证也触发并通过了原锁备份与定向刷新，未运行其中第三方插件。这些结果不代表已经验证所有插件组合或模型请求。

普通测试涵盖新旧帮助形状、未知参数、SemVer 排序、profile/版本路径约束、包内 bin 路径、隔离环境、自定义能力配置和插件未知字段保留。发行清单新增测试检查跨会话复用、切换下载源、缓存过期与损坏、部分查询失败、元数据版本不符，以及取消时中断请求且不发布部分缓存。

联网集成测试默认跳过。开发者显式执行：

```powershell
mvn '-Dtest=com.hdsl.runtime.RuntimeIntegrationTest' '-Dhdsl.integration=true' test
```

测试目录固定为项目内 `.test-data/compatibility`；需要其 `tools/node` 与 `tools/pnpm` 已准备好。只启动临时空白 profile，确认旧版 HTTP 200、新版鉴权响应，再测试本机无脚本 fixture 插件的安装和移除。不会登录模型服务、执行模型请求或使用用户真实凭据。结果保存在该目录的 `integration-report.md`；默认普通构建不运行这些联网安装。原始临时运行日志不进入发布包或研究报告。

## 上游依据

2026-10-02 直接查询 npm 时，`latest` 为 `0.2.0-rc.2`。它仍是预发布版。旧版对照为原启动器使用的 `0.1.0-rc.6`。

- [官方 CLI 行为参考](https://github.com/deepseek-ai/deepseek-harness/blob/master/apps/cli/reference/README.md)：profile、插件对账、应用参数的分工。
- [官方 CLI 参数实现](https://github.com/deepseek-ai/deepseek-harness/blob/master/apps/cli/src/args.ts)：顶层和应用帮助，以及 profile 参数顺序。
- [官方插件发布说明](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/user/develop/basic/publish.md)：`dsh.bundle` 与 profile 的关系。
- [npm 包清单](https://registry.npmjs.org/@deepseek-ai%2fdsh)：实际可下载版本及 bin 元数据。
- [pnpm install](https://pnpm.io/cli/install)：冻结锁文件与忽略安装脚本的行为。
- [上游 npm 依赖解析问题及 pnpm 复现](https://github.com/deepseek-ai/deepseek-harness/discussions/4872)：实际的 peer 依赖安装要求。
- [pnpm 10 的 resolutionMode](https://github.com/pnpm/pnpm.io/blob/main/versioned_docs/version-10.x/settings.md#resolutionmode)：按发行时间解析依赖的行为。

旧版 Windows 转发行为另由 `@deepseek-ai/dsh@0.1.0-rc.6` 发布包中的 `lib/plugin-9h8shc4d.js` 核对。
