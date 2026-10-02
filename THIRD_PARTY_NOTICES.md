# 许可与第三方声明 / License and third-party notices

HDSL 0.2.0-preview.5 包含从 Hello Minecraft! Launcher（HMCL）移植并修改的 JavaFX 界面代码。组合后的 HDSL 程序按 **GNU GPL 第 3 版或任何后续版本**发布，并遵守 HMCL 的附加条款。GPL 全文见根目录 [LICENSE](LICENSE)（Windows 包中为 `LICENSE-HDSL.txt`），附加条款见 [LICENSES/HMCL-ADDITIONAL-TERMS.md](LICENSES/HMCL-ADDITIONAL-TERMS.md)。

HDSL includes modified JavaFX interface code from HMCL. The combined application is distributed under **GPL-3.0-or-later**, with HMCL's additional terms: distinguish modified distributions by name or version, and retain the copyright notice displayed in the application. See the full license and additional terms above. HDSL is a separate modified product and is not an official HMCL release.

- HDSL: Copyright (c) 2026 jiefing.
- HMCL: Copyright © 2013–2026 huangyuhui and contributors. Individual source files retain their original copyright notices.
- HDSL 的原始 MIT 许可完整保存在 [LICENSES/HDSL-MIT.txt](LICENSES/HDSL-MIT.txt)，继续适用于原先按 MIT 提供的代码；该文件不将 HMCL 代码或组合后的程序重新许可为 MIT。The original MIT grant remains available for the original MIT-covered HDSL code.

## HMCL 来源 / HMCL origin

上游固定版本：[HMCL `77eee17d361996259a48cc7896006a57d2e34a2a`](https://github.com/HMCL-dev/HMCL/tree/77eee17d361996259a48cc7896006a57d2e34a2a)。文件对照、修改说明、背景图核查及界面声明要求见 [docs/HMCL_PORT.md](docs/HMCL_PORT.md)。移植文件保留上游 GPL 头、来源路径和修改日期。

本次未采用 HMCL 的 `2021-08-26.jpg` 背景图或 HMCL 品牌图标。该背景图的具体作者与再分发许可尚未核实，核查经过保留在移植记录中。

## HDSL 生成素材 / Generated HDSL artwork

preview.3 的鲸鱼娘图标与五张内置背景由内置 `image_gen` 工具生成。最终图标按用户提供的人物参考修正；五张背景结合该参考和修正后的图标统一形象，保留各自场景。Windows ICO 由图标缩放转换，界面头像使用同一 PNG。素材清单、实际尺寸、提示词与参考关系见 [assets/artwork/README.md](assets/artwork/README.md)。这些具体图片是本次为 HDSL 生成的素材，不是 HMCL 资产或 DeepSeek 官方形象。

The preview.3 mascot icon and five backgrounds were AI-generated with the built-in `image_gen` tool. The final artwork uses the user's character references and the corrected HDSL icon. The ICO is a resized conversion; the in-app avatar uses the same PNG. These images are HDSL artwork, not HMCL assets or an official DeepSeek character. The linked artwork record retains the prompts and references.

## 随程序分发的依赖 / Bundled dependencies

以下依赖保留各自许可，不因与 HDSL 一起分发而改变。版本来自当前 `pom.xml` 和打包脚本。`LICENSES/dependencies/` 保存相应声明副本；JAR 内原有 `META-INF` 声明也应保留。

| 组件 / Component | 版本 / Version | 许可与声明 / License and notices |
| --- | --- | --- |
| [OpenJFX](https://github.com/openjdk/jfx21u/tree/21.0.8%2B2)：base、controls、graphics、swing | 21.0.8 | GPLv2 with Classpath Exception；[LICENSES/dependencies/openjfx-21.0.8/](LICENSES/dependencies/openjfx-21.0.8/) 同时保留 assembly exception、JPEG 与 Mesa 声明。 |
| [Jackson](https://github.com/FasterXML/jackson-databind/tree/jackson-databind-2.19.2)：annotations、core、databind | 2.19.2 | Apache-2.0；各模块的 LICENSE / NOTICE 已保留，core 内 FastDoubleParser 和其他第三方声明也保留。 |
| [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml/src/snakeyaml-2.4/) | 2.4 | Apache-2.0；[LICENSE](LICENSES/dependencies/snakeyaml-2.4/LICENSE.txt)。 |
| [JNA / JNA Platform](https://github.com/java-native-access/jna/tree/5.17.0) | 5.17.0 | 上游许可为 Apache-2.0 OR LGPL-2.1-or-later；HDSL 采用 Apache-2.0 选项。两个实际 JAR 的原始 LICENSE 分别保存在 `LICENSES/dependencies/jna-5.17.0/` 和 `jna-platform-5.17.0/`，并附 AL2.0 全文。用于调用 Windows DPAPI。 |
| [Apache Commons](https://commons.apache.org/)：Compress / IO / Codec / Lang | 1.28.0 / 2.20.0 / 1.19.0 / 3.18.0 | Apache-2.0；各模块的 LICENSE.txt 和 NOTICE.txt 已保留。 |
| [Node.js](https://nodejs.org/dist/v24.21.0/) | 24.21.0 | MIT 及所含第三方许可；Windows 包完整保留 `tools/node/LICENSE` 和 npm/Corepack 各自许可。 |
| [pnpm](https://github.com/pnpm/pnpm/tree/v10.34.0) | 10.34.0 | MIT 及包内第三方声明；Windows 包保留 `tools/pnpm/node_modules/pnpm/LICENSE`。 |

Windows 公开便携包使用 Eclipse Temurin OpenJDK 21.0.12.1+1 的 `jpackage` 组装。Java 运行环境按 GPLv2 with Classpath Exception 及所含第三方许可分发，原始声明保留在 `runtime/legal/`。本次发行同时提供该 Temurin 版本和 OpenJFX 21.0.8+2 的完整对应源码附件、固定来源及 SHA-256，见 [docs/DEPENDENCY_SOURCES.md](docs/DEPENDENCY_SOURCES.md)。HDSL 的 GPL 声明不替代各依赖的许可。

The public Windows package uses Eclipse Temurin OpenJDK 21.0.12.1+1, under GPLv2 with the Classpath Exception and included third-party terms. Runtime notices remain under `runtime/legal/`. This release provides the complete corresponding Temurin and OpenJFX 21.0.8+2 source archives as separate assets; see the source record above for fixed origins and checksums.

## 源码与外部程序 / Source and external programs

Windows 包附本版本对应源码：`sources/hdsl-0.2.0-preview.5-source.zip`，包含修改后的程序、资源、构建脚本和许可说明。嵌入 JAR 的许可文档不能替代完整对应源码包。后续分发修改版时应同步提供对应版本的完整源码；仅提供 HMCL 上游地址不足以提供 HDSL 修改部分。

The Windows package includes the corresponding HDSL source and build scripts at the path above. Keep the source archive aligned with the binaries when redistributing a modified build.

Harness、用户安装的插件和整合包单独下载、安装，受各自许可约束。PackForge 与 Overture 的格式研究不表示其整套程序或用户数据随 HDSL 发布；本项目的导入适配器由 HDSL 实现。开发工具、研究仓库、测试下载内容和用户实例目录不属于 HDSL 源码包。

插件发现读取 GitHub 公开仓库和 npm 发布元数据，不把检索到的社区项目作为 HDSL 自身代码。桌面下载以 [DeepSeek 官网](https://www.deepseek.com/en/download/)为官方来源，另提供 `anywhere-labs/dsh-desktop` 与 `dataelement/dsh-desktop` 两个明确标注的社区来源。官网下载与 GitHub Release 是不同入口。下载得到的独立应用遵守其发布方许可，由用户自行安装；账户与插件仍由该应用管理。
