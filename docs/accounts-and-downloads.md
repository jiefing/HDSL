# 账户与下载中心

预发布版本：0.2.0-preview.5。实现与上游核查日期：2026-10-02。

## 使用入口

侧栏按「账户、实例、通用」分组。「账户」可以添加多个 API Key 账户；创建、编辑实例，或在账户页为当前实例选择一个账户。「实例 → 下载」包含 Web 版、桌面版和插件三个页面。

### 账户

- 账户保存名称、提供商标识、API 地址、模型和加密密钥。预设方便填写；运行时会检查已安装 Harness 是否支持所选提供商。自定义协议提供 OpenAI Chat Completions、OpenAI Responses 和 Anthropic Messages。
- 选择 `deepseek-official` 并使用 DeepSeek 官方标准 API 地址时，启动器会按实际 Harness 使用的协议适配请求地址。第三方或自定义地址保持原样。
- 保存账户不发送模型请求，也不证明密钥、余额或模型权限有效。界面明确显示「API 连接未验证」。订阅制 OAuth 登录不能直接作为 API Key 使用。
- 每个实例保存账户 ID，不保存密钥副本。改名、复制实例和版本切换会保留账户引用；分享整合包不包含启动器的账户文件。
- 修改绑定需停止实例，下次启动生效。取消绑定后使用实例自身配置。仍被实例引用的账户不能删除。
- Windows 使用当前用户作用域的 DPAPI 加密 `config/accounts.json` 内的密钥。复制便携目录到另一用户或计算机后，需重新输入密钥。其他平台当前没有统一账户加密后端。
- 界面状态中没有密钥或密钥片段。输入框提交、关闭后清空；子进程的输出按敏感环境变量的完整值及常见密钥格式脱敏。

### 桌面下载

默认来源是 [DeepSeek 官方下载页](https://www.deepseek.com/en/download/)，从页面读取 Windows 桌面版链接，再获取实际文件信息；社区 `anywhere-labs/dsh-desktop` 和 `dataelement/dsh-desktop` 是另外两个可选来源，使用 GitHub Releases 列表。官网入口与 GitHub Releases 分别查询，避免遗漏官网独立发布的桌面版。

筛选 Windows x64 安装文件，稳定版优先。下载至 `downloads/desktop/`；进度使用已收到字节和发布大小，支持取消。完整下载后检查大小；发布方提供 SHA-256 时必须匹配，未提供时显示此限制并保存本地摘要。再次显示「已下载」或打开文件夹前复核摘要，损坏的文件需重新下载。本地摘要用于检查缓存是否变化，不代表发布方认证。验证失败不会覆盖已有文件，正常取消会清理本次临时文件。

下载完成后可打开文件夹，由用户运行安装程序。桌面版是独立应用，其账户、工作区和插件在该应用内部管理；HDSL 的实例账户不会自动同步到它。

### 插件目录

来源是用户指定的 [dsh-plugin 主题](https://github.com/topics/dsh-plugin)。GitHub 主题用于发现项目，不能单独证明项目可安装。

1. 搜索、分页获取仓库名称、描述、星数和地址。
2. 选择「检查安装目标」，读取公开仓库包声明及明确的 workspace 范围。
3. 核对 npm 发布的仓库地址、子目录、`dsh.bundle.patch`、版本及下载元数据。
4. 对已确认的目标，显示精确 `包名@版本`，选择实例后调用该实例的 Harness 插件管理命令。

无法确认的项目保留「查看项目」入口。安装目标核对不等于插件功能或运行兼容性已经验证。GitHub 限流或断网会提示；有缓存时保留原结果并标明缓存。大型 workspace 每次最多检查 12 个候选包，GitHub 搜索结果上限会显示在目录中。

## 维护接口

- `AccountService` 管理账户元数据和 DPAPI 密文；`AccountOverlay` 根据已安装的公开包识别适配方式；`LaunchBinding` 持有一次启动的临时配置和密钥环境变量。
- 账户启动配置位于实例 `.hdsl-account/<随机目录>`，其中不写密钥。保持到实例退出后清理，避免上游热重载时失去引用。未知配置结构明确失败，避免静默使用错误账户。
- 启动时检查实际默认提供商、模型、API 地址和账户环境变量引用。检查失败会停止本次实例；已存在的会话可能保留自己明确选择的模型，账户绑定设置的是默认模型及所选提供商凭据。
- `DesktopDownloadService` 从 DeepSeek 官网读取官方链接，从 GitHub Releases 读取社区发行元数据。下载只允许 HTTPS `download.deepseek.com`、GitHub 及其发行文件域名，限制重定向次数、响应大小、总时长；不会执行安装包。官网下载发送 `If-Match`，并核对下载响应的 ETag 与查询时一致；文件发生变化时要求刷新后重新下载。
- `PluginCatalogService` 只读取公开元数据，不访问 GitHub 登录凭据。搜索和目标解析分开，采用有限缓存及有界请求。`Controller` 只允许安装已经解析得到的目标。
- `UiState` 是公开展示数据；不得向其中增加密钥、完整环境变量或凭据文件内容。
- 打包脚本只包含程序、依赖、说明及源码。`config`、`instances`、Harness `runtimes`、桌面下载、测试资料和日志不进入分发包。

## 上游依据

- [DeepSeek Harness 官方仓库](https://github.com/deepseek-ai/deepseek-harness)：Web 启动方式、插件生态、源码与发行记录。
- [核查使用的上游提交](https://github.com/deepseek-ai/deepseek-harness/tree/639ed015397290b3745d163aafe02ffee4aa3f84)：`packages/bundle/base/cordis.patch.yml`、模型插件和默认模型配置。
- 已发布的 `0.1.0-rc.6` 和 `0.2.0-rc.2` npm 包：启动器实际适配的配置行为以安装包为准。
- [anywhere-labs 桌面项目](https://github.com/anywhere-labs/dsh-desktop)和[dataelement 桌面项目](https://github.com/dataelement/dsh-desktop)：各自发布的安装包及应用说明。

具体测试结果见 [VALIDATION.md](VALIDATION.md)。
