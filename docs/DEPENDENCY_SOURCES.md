# 运行环境与对应源码

HDSL `v0.2.0-preview.5` 的 Windows x64 便携包使用下列固定版本。本页列出的源码附件和 SHA-256 文件与便携包一起提供于 [GitHub 发行页面](https://github.com/jiefing/HDSL/releases/tag/v0.2.0-preview.5)。下载并使用启动器只需 Windows 便携 ZIP；源码附件供查阅、构建和再分发使用。

## HDSL

- 修改后的完整应用源码、资源、测试、构建脚本及许可证：`hdsl-0.2.0-preview.5-source.zip`。
- 同一源码包也在便携包的 `sources/` 下；其 SHA-256 保存在 `build-info.json` 和相邻 `.sha256` 文件。
- 许可：GPL-3.0-or-later 及 HMCL 附加条款；原 HDSL MIT 声明保留。详见 [第三方声明](../THIRD_PARTY_NOTICES.md)。

## Eclipse Temurin OpenJDK

- 版本：`21.0.12.1+1`，Windows x64 HotSpot。
- [官方固定发行](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.12.1%2B1)。
- 构建及打包使用 [OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip](https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip)，官方 SHA-256：`f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e`。
- 完整对应源码附件：`OpenJDK21U-jdk-sources_21.0.12.1_1.tar.gz`，来自 [同一官方发行的源码资产](https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk-sources_21.0.12.1_1.tar.gz)。官方 SHA-256：`573057d03584ae793fb7ec9a14c76d826d9187a53efeefd99da47403a5308234`。
- 该附件是完整上游源码树，包含原生代码和构建文件；不以 JDK 的 `lib/src.zip` 替代。
- `jpackage` 根据应用生成运行环境；HDSL 未修改 OpenJDK 实现。运行环境原始许可和第三方声明保留在便携包的 `runtime/legal/`；许可为 GPLv2 with Classpath Exception 及所含第三方条款。

## OpenJFX

- 依赖版本：`21.0.8`；使用 Maven Central 发布的 base、graphics、controls、swing JAR。
- 上游标签：[21.0.8+2](https://github.com/openjdk/jfx21u/tree/21.0.8%2B2)，对应提交 `3bbff12986916db4f9416853012a298f690ba43b`。
- 完整对应源码附件：`OpenJFX21U-sources_21.0.8_2-3bbff1298691.tar.gz`，来自 [上游固定提交归档](https://codeload.github.com/openjdk/jfx21u/tar.gz/3bbff12986916db4f9416853012a298f690ba43b)，包含 Java、原生实现和构建文件。
- 下载后计算的 SHA-256 为 `38af69452774ca95c02da7832d2ed48845251b62e9bdd1c958bbd4b6fdff5a2a`，也保存在相邻 `.sha256` 文件；该值是本次发布的完整性校验值，不是上游另行发布的摘要。
- HDSL 未修改 OpenJFX 实现。GPLv2 with Classpath Exception、assembly exception 及所用原生组件的许可保留在 `LICENSES/dependencies/openjfx-21.0.8/` 和依赖 JAR 内。

## 本地重新构建

使用 JDK 21 和 Maven，执行 `mvn -B -ntp verify`。Windows 可用 `scripts/build.ps1 -JdkHome <JDK目录>` 完成构建，再用 `scripts/package-windows.ps1 -JdkHome <JDK目录> -Destination <新目录>` 生成便携目录。打包所用 Java 发行方和版本记录在 `build-info.json`；Node 和 pnpm 的固定版本及下载方式见打包脚本。

源码和二进制归档采用明确的文件清单；账户、API Key、用户实例、Harness 下载内容、日志及本机测试资料不属于发行内容。
