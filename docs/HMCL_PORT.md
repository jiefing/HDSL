# HMCL 界面移植与许可记录 / HMCL port record

记录日期：2026-10-02。界面移植始于 HDSL 0.2.0-preview.2，本记录更新至 0.2.0-preview.5。

## 来源与适用许可

本版实际移植并修改 HMCL 界面代码。上游仓库为 [HMCL-dev/HMCL](https://github.com/HMCL-dev/HMCL)，固定提交为 [`77eee17d361996259a48cc7896006a57d2e34a2a`](https://github.com/HMCL-dev/HMCL/tree/77eee17d361996259a48cc7896006a57d2e34a2a)。下表路径均相对于该提交。

移植源文件的头部声明为 GPL 第 3 版或任何后续版本。上游 [LICENSE](https://github.com/HMCL-dev/HMCL/blob/77eee17d361996259a48cc7896006a57d2e34a2a/LICENSE) 提供 GPLv3 全文；[README](https://github.com/HMCL-dev/HMCL/blob/77eee17d361996259a48cc7896006a57d2e34a2a/docs/README.md#license) 另附两项 Section 7 条款：修改版须以名称或版本号区别原版，且不得删除程序显示的版权声明。HDSL 采用 GPL-3.0-or-later 并保留这些条款。

核查时，根目录 `LICENSE` 是上游 GPL 全文的逐字节副本，SHA-256 为 `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986`。原始 HDSL MIT 文件保存为 `LICENSES/HDSL-MIT.txt`，SHA-256 为 `aadd31fc875b4c3d7764cef8aba3a2f4f1e3f0362418261df99f1775d61fc0a5`。这些哈希记录本次工作区文件；Git 换行转换可能改变字节哈希。原 MIT 授权继续适用于其原有代码，组合后的程序按上述 GPL 条件分发。

This is an actual source port from the fixed HMCL revision, distributed as part of HDSL under GPL-3.0-or-later with the upstream Section 7 terms. Original HDSL MIT notices remain available for the code originally covered by that grant.

## 文件对照 / Source mapping

Java 上游前缀为 `HMCL/src/main/java/org/jackhuang/hmcl/ui/`，HDSL 目标前缀为 `src/main/java/com/hdsl/ui/hmcl/`。移植文件保留 huangyuhui 与贡献者版权、GPL 头部、固定提交、上游路径及 `2026-10-02` 修改日期。

| 上游相对路径 / Upstream path | HDSL 文件 / Target file | 本次修改 / Adaptation |
| --- | --- | --- |
| `construct/AdvancedListBox.java` | `AdvancedListBox.java` | 分类侧栏与列表容器；迁移包名及所需控件接口。 |
| `construct/AdvancedListItem.java` | `AdvancedListItem.java` | 双行项目属性与选中状态；连接 Harness 导航和实例操作。 |
| `construct/AdvancedListItemSkin.java` | `AdvancedListItemSkin.java` | 标准 JavaFX skin，适配保留的属性与布局。 |
| `construct/ClassTitle.java` | `ClassTitle.java` | 保留分类标题布局，替换 HMCL 专用辅助函数。 |
| `construct/TwoLineListItem.java` | `TwoLineListItem.java` | 保留双行项目构造与属性绑定，适配 JavaFX 接口。 |
| `construct/RipplerContainer.java` | `RipplerContainer.java` | 保留内容容器与悬停层；JavaFX 动画替代 JFoenix。 |
| `decorator/DecoratorAnimatedPage.java` | `DecoratorAnimatedPage.java` | 保留带侧栏的页面骨架，简化 HMCL 导航依赖。 |
| `decorator/MainWindowPane.java` | `MainWindowPane.java` | 保留窗口装饰布局，接入 HDSL 窗口操作和许可入口。 |
| `main/RootPage.java` | `RootPage.java` | 保留 200px 分类侧栏布局；账户、游戏入口改为 Harness 工作空间、实例、运行时、整合包和插件。 |
| `main/MainPage.java` | `MainPage.java` | 保留右下启动区域、双行按钮、菜单切换与箭头动画；替换为 Harness 启停回调。 |

资源上游前缀为 `HMCL/src/main/resources/assets/`，HDSL 目标前缀为 `src/main/resources/com/hdsl/ui/`。

| 上游相对路径 / Upstream path | HDSL 文件 / Target file | 本次修改 / Adaptation |
| --- | --- | --- |
| `css/root.css` | `hmcl-root.css` | 摘取启动器、侧栏、标题栏与卡片规则，移除 JFoenix 专用样式。 |
| `css/blue.css` | `hmcl-blue.css` | 保留默认颜色表，添加来源说明。 |
| 上游仓库根 `LICENSE` | `GPL-3.0.txt` | 原样提供 GPL 全文，供界面内查看。 |

移植控件通过 HDSL 的 `ShellView`、`UiState` 和 `UiActions` 连接现有业务。Minecraft 账户、游戏下载、认证代码不在本次移植范围内。JFoenix 等 HMCL 专用控件替换为标准 JavaFX；依赖列表以 HDSL 的 `pom.xml` 为准。`launcher.css` 是 HDSL 集成样式。

preview.4 在上述控件上增加账户、实例、通用三组导航。API Key 账户及 Windows DPAPI 加密、公开插件目录核验、独立桌面端发行包下载由 HDSL 实现，没有移植 HMCL 的 Minecraft 账户认证流程。功能与范围见 [账户与下载说明](accounts-and-downloads.md)。

## 界面版权与名称

上游可见版权来自 [`I18N_zh_Hans.properties`](https://github.com/HMCL-dev/HMCL/blob/77eee17d361996259a48cc7896006a57d2e34a2a/HMCL/src/main/resources/assets/lang/I18N_zh_Hans.properties)，由上游 `ui/main/AboutPage.java` 的版权区域展示：

> 版权所有 © 2013-2026 huangyuhui 及贡献者

HDSL 的许可/关于入口应保留该声明，同时显示 HDSL 的名称、版本、`Copyright (c) 2026 jiefing`、GPL 许可与附加条款入口。GPL 的无担保说明、源码和许可文本应可查阅。后续界面重构须继续保留这些内容；发布前须检查实际界面，不能只检查源文件头。

The HDSL name and independent version distinguish this modified application. Retain the original visible HMCL copyright notice alongside HDSL attribution and an accessible license/source entry.

## 背景图与其他资源

preview.2 使用 HDSL 原有代码绘制的山景。preview.3 新增由内置 `image_gen` 工具生成的鲸鱼娘图标与五张背景，提供设置页缩略图选择，并保留已有自定义图片路径。生成素材清单与参考关系见 [assets/artwork/README.md](../assets/artwork/README.md)。这些具体图片不是 HMCL 资产或 DeepSeek 官方形象。

两版均未采用 HMCL 的 `HMCL/src/main/resources/assets/img/wallpapers/2021-08-26.jpg`。以下保留原核查记录：

- 固定提交中的文件大小为 204,702 字节，SHA-256 为 `0514620c55e7344a525a8ec511e458aeddb65b7e8f083ec61ad98d74fb9c8b36`。
- [`647bbb38e83c2772f2e44b0e0f29ca6216e56440`](https://github.com/HMCL-dev/HMCL/commit/647bbb38e83c2772f2e44b0e0f29ca6216e56440) 在 2021-08-25 引入 `assets/img/background.jpg`。
- [`28022461f7665720889d006ce14e671519d80de7`](https://github.com/HMCL-dev/HMCL/commit/28022461f7665720889d006ce14e671519d80de7) 在 2026-06-29 将该文件移至现有路径。
- [上游讨论 #3894](https://github.com/HMCL-dev/HMCL/discussions/3894) 在 2025-05-05 提供原图备份，但所查页面没有明确该图作者及单独的再分发授权。
- 固定提交的 [`assets/about/thanks.json`](https://github.com/HMCL-dev/HMCL/blob/77eee17d361996259a48cc7896006a57d2e34a2a/HMCL/src/main/resources/assets/about/thanks.json) 鸣谢 gamerteam、Red_lnn 提供默认背景图，未将本图与具体贡献者、许可对应。

未复制 HMCL 标识图、字体或其他照片。以后采用该背景图前需补上可核验的作者和许可记录。

The excluded wallpaper has an upstream history and a later original-image backup, but the records reviewed did not establish its specific author and redistribution permission. Preview.2 used a landscape drawn by HDSL code. Preview.3 adds AI-generated HDSL mascot artwork and five selectable backgrounds; their generation record is separate from the HMCL source port.

## 分发、源码与验证

- 许可全文、原 MIT 声明、HMCL 附加条款和第三方声明随源代码与 Windows 包保留，并嵌入应用 JAR 的 `META-INF/`。
- Windows 包附 `sources/hdsl-0.2.0-preview.5-source.zip`，包含对应修改源码、资源、构建脚本、测试和许可文件。以打包后的 `build-info.json` 中 `sourceSha256` 核对实际源码包。
- 完整对应源码必须对应所分发的二进制与修改；HMCL 上游链接用于溯源，不能替代 HDSL 自身对应源码。参考仓库、下载的测试整合包、用户实例和凭据不纳入源码包。
- 第三方库和独立运行环境保留其原有许可；见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。公开便携包使用 Eclipse Temurin OpenJDK 21.0.12.1+1，并随发行提供该运行环境与 OpenJFX 的完整对应源码，来源及校验记录见 [DEPENDENCY_SOURCES.md](DEPENDENCY_SOURCES.md)。
- 本记录完成了文本、来源与文件头核查。实际窗口、打包后的许可入口、资源和源码包的检查结果由发布验收记录另行给出。

## 依赖声明的取证来源

`LICENSES/dependencies/` 中 Commons 和 Jackson 声明逐字节提取自当前 Maven 依赖 JAR 的 `META-INF`。SnakeYAML 的许可证来自 [2.4 标签下 LICENSE.txt](https://bitbucket.org/snakeyaml/snakeyaml/src/snakeyaml-2.4/LICENSE.txt)。OpenJFX 文件来自官方 [`21.0.8+2`](https://github.com/openjdk/jfx21u/tree/21.0.8%2B2) 的根目录 `LICENSE`、`ADDITIONAL_LICENSE_INFO`、`ASSEMBLY_EXCEPTION` 及所用 graphics 模块 `src/main/legal/` 下 JPEG、Mesa 声明。Node.js 与 pnpm 的原始许可随其工具目录保留。

preview.4 新增 JNA 与 JNA Platform 5.17.0，用于 Windows DPAPI。两个 `META-INF/LICENSE` 均从实际 Maven JAR 逐字节提取，Apache 许可全文来自上游 [5.17.0 标签的 AL2.0](https://github.com/java-native-access/jna/blob/5.17.0/AL2.0)，分别保存在对应 `LICENSES/dependencies/` 目录。
