<p align="center">
  <img src="assets/hero-banner-v1.png" alt="HDSL hero banner" width="100%">
</p>

<h1 align="center">HDSL · hello deepseek harness launcher</h1>

<p align="center"><b>像Minecraft 启动器一样管理DeepSeek Harness实例</b><br>
<em>Manage DeepSeek Harness instances like a Minecraft launcher</em></p>

<p align="center">
  <b>简体中文</b> | <a href="README_EN.md">English</a>
</p>

<p align="center">
  <img alt="Version" src="https://img.shields.io/badge/version-0.1-4d6bfe?style=flat-square">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Windows%20%7C%20Linux-4d6bfe?style=flat-square">
  <img alt="Status" src="https://img.shields.io/badge/status-public%20beta-7da1de?style=flat-square">
  <a href="https://github.com/deepseek-ai/deepseek-harness"><img alt="DSH" src="https://img.shields.io/badge/DSH-DeepSeek%20Harness-5B4CF0?style=flat-square"></a>
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/license-MIT-yellow?style=flat-square"></a>
  <img alt="topic" src="https://img.shields.io/badge/topic-dsh--plugin-0b7285?style=flat-square">
</p>

> HDSL 为 DeepSeek Harness 提供本地桌面体验：启动后显示原生 GUI 主窗口，不自动打开浏览器；在 GUI 中管理运行时、实例、插件、主题背景和备份，并从 GUI 启动 Harness。
>
> 本项目是社区项目，与 DeepSeek 官方无关联。

##  功能一览

- ** 实例管理**：创建 / 复制 / 删除实例；每个实例分配独立回环端口（默认 3080），旧配置自动迁移、端口冲突自动规避
- ** 运行时管理**：通过内置 Node / pnpm 按需安装指定版本的 dsh（默认 0.1.0-rc.6），版本彼此隔离，无全局 npm 安装
- ** 插件管理**：插件清单即时缓存，普通导航零延迟、不调用 dsh；支持按 ID / 包名 / 版本搜索，可一键隐藏官方插件（@deepseek-ai/*），过滤仅为视图操作，不改动缓存
- ** 一键启动 / 停止**：从 GUI 启动实例；停止时完整终止整个进程树（含包装 shell 与子进程），Windows / Linux 行为一致
- ** 安全进程验证**：以 PID + 端口 + 实例工作区所有权三重校验，绝不误杀无关进程；仅占用端口但无法验证所有权的监听者报告为 port occupied
- ** 主题与背景**：自定义 hero 背景图，随实例切换显示
- ** 便携式数据布局**：config / plugins / background / backups / data / logs / cache / runtimes / tools / instances 全部存放在程序同级目录，拷贝即用
- ** 中英双语界面**：内置中文 / English 文案

## 📸 界面预览

![HDSL 主界面预览](assets/preview.png)

## 快速开始

### 用户：直接下载

从本仓库的 Releases 页面下载 HDSL-desktop-windows-<版本>.zip，解压后运行 HDSL.exe 即可（已捆绑 Java 运行时与 Node 工具链，无需预装 JDK）。

### 开发者：从源码构建

前置条件：JDK 25。

    & "C:\Program Files\Java\jdk-25.0.2\bin\javac.exe" -encoding UTF-8 -d build\classes src\com\hdsl\Launcher.java
    & "C:\Program Files\Java\jdk-25.0.2\bin\jar.exe" --create --file build\hdsl-client.jar --main-class com.hdsl.Launcher -C build\classes .
    & "C:\Program Files\Java\jdk-25.0.2\bin\jpackage.exe" --type app-image --input build --main-jar hdsl-client.jar --main-class com.hdsl.Launcher --name HDSL --dest build\image

运行冒烟测试：

    & "C:\Program Files\Java\jdk-25.0.2\bin\javac.exe" -encoding UTF-8 -cp build\classes -d build\test-classes test\com\hdsl\*.java
    & "C:\Program Files\Java\jdk-25.0.2\bin\java.exe" -cp build\classes;build\test-classes com.hdsl.SidebarLayoutSmokeTest

## 使用流程

1. 启动 HDSL，在实例库中创建实例（名称 + Harness 版本）
2. 选中实例，点击启动：HDSL 自动安装对应版本的 dsh 到 runtimes/<版本>，并初始化独立的 instances/<id>/workspace 与 dsh-home
3. 实例运行后，主界面实时显示状态与回环端口；停止操作会完整终止进程树
4. 插件页可浏览 / 搜索 / 过滤插件；添加 / 更新 / 移除插件后缓存自动刷新

## 常见问题

| 现象 | 说明 |
|---|---|
| 提示 port occupied | 端口被未经验证的进程占用。HDSL 不会杀死它；请手动结束该进程后重试 |
| 插件页显示旧数据 | 普通导航读取即时缓存；应用启动、切换实例、插件变更、实例启动、手动刷新时会后台刷新 |
| 想隐藏官方插件 | 插件页开启 Hide official plugins 开关，仅过滤视图，不影响缓存与安装 |
| 需要预装 Java 吗 | 不需要。打包产物已捆绑运行时；从源码构建才需要 JDK 25 |
| 如何升级 | 下载新版本 ZIP 覆盖旧目录即可，config / data / instances 等数据目录会保留 |
| 卸载 | 删除程序目录即完成卸载，不写入系统注册表 |

## 仓库结构

- src/com/hdsl/Launcher.java — 单文件桌面客户端（UI、实例、运行时、插件、进程控制）
- test/com/hdsl/ — 冒烟测试与生命周期集成测试
- assets/ — 应用图标与 hero 横幅
- VERSION — 发布版本号

## 最近更新

### v0.1（2026-08-17）— 首次公开源码发布

本次发布包含：

- 每个实例独立回环端口，旧记录自动迁移
- 运行时状态轮询不阻塞 UI；PID + 端口 + 工作区所有权安全验证
- 停止实例完整终止进程树（Windows / Linux）
- 插件清单即时缓存与后台刷新机制
- 插件搜索与官方插件过滤
- 自包含、版本隔离的可移植架构

## 社区与反馈

- 问题与建议：欢迎提交 Issues
- 本仓库带有 dsh-plugin topic，可在 [GitHub dsh-plugin 主题页](https://github.com/topics/dsh-plugin) 找到

## 许可

本项目采用 MIT License（见 LICENSE 文件）。Copyright (c) 2026 jiefing。

