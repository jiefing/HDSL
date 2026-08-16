<p align="center">
  <img src="assets/hero-banner-v1.png" alt="HDSL hero banner" width="100%">
</p>

<h1 align="center">HDSL · hello deepseek harness launcher</h1>

<p align="center"><b>Manage DeepSeek Harness instances like a Minecraft launcher</b><br>
<em>像Minecraft 启动器一样管理DeepSeek Harness实例</em></p>

<p align="center">
  <a href="README.md">简体中文</a> | <b>English</b>
</p>

<p align="center">
  <img alt="Version" src="https://img.shields.io/badge/version-0.1-4d6bfe?style=flat-square">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Windows%20%7C%20Linux-4d6bfe?style=flat-square">
  <img alt="Status" src="https://img.shields.io/badge/status-public%20beta-7da1de?style=flat-square">
  <a href="https://github.com/deepseek-ai/deepseek-harness"><img alt="DSH" src="https://img.shields.io/badge/DSH-DeepSeek%20Harness-5B4CF0?style=flat-square"></a>
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/license-MIT-yellow?style=flat-square"></a>
  <img alt="topic" src="https://img.shields.io/badge/topic-dsh--plugin-0b7285?style=flat-square">
</p>

> HDSL gives DeepSeek Harness a native desktop experience: a real GUI window on launch (no browser, no local HTTP server required for the main UI). Manage runtimes, instances, plugins, themes, and backups from the GUI, and launch Harness with one click.
>
> This is a community project and is not affiliated with DeepSeek.

## ✨ Features

- **🗂️ Instance management**: create / duplicate / delete instances. Every instance gets a unique loopback web port (default 3080); legacy records migrate automatically and port conflicts are avoided
- **⚙️ Runtime management**: installs the exact dsh version you choose (default 0.1.0-rc.6) on demand through the bundled Node / pnpm toolchain. Versions are isolated per runtime; no global npm installs
- **🧩 Plugin management**: plugin inventories render instantly from a per-instance cache — ordinary navigation never invokes dsh. Search by ID / package name / version and one-click hide of official plugins (@deepseek-ai/*); filtering is view-only and never mutates the cache
- **🚀 One-click start / stop**: launch instances from the GUI; stopping cleanly terminates the entire process tree (wrapper shells and child processes) on both Windows and Linux
- **🛡️ Safe ownership verification**: PID + port + instance workspace ownership checks — an unrelated process merely occupying a port is reported as port occupied and is never killed
- **🖼️ Themes & backgrounds**: pick a custom hero background, shown per instance
- **💾 Portable data layout**: config / plugins / background / backups / data / logs / cache / runtimes / tools / instances all live beside the executable — copy the folder and it just works
- **🌏 Bilingual UI**: built-in Chinese / English texts

## Quick start

### Users: download

Grab HDSL-desktop-windows-<version>.zip from the Releases page of this repository, extract it, and run HDSL.exe. The Java runtime and Node toolchain are bundled — no JDK required.

### Developers: build from source

Prerequisites: JDK 25.

    & "C:\Program Files\Java\jdk-25.0.2\bin\javac.exe" -encoding UTF-8 -d build\classes src\com\hdsl\Launcher.java
    & "C:\Program Files\Java\jdk-25.0.2\bin\jar.exe" --create --file build\hdsl-client.jar --main-class com.hdsl.Launcher -C build\classes .
    & "C:\Program Files\Java\jdk-25.0.2\bin\jpackage.exe" --type app-image --input build --main-jar hdsl-client.jar --main-class com.hdsl.Launcher --name HDSL --dest build\image

Run the smoke tests:

    & "C:\Program Files\Java\jdk-25.0.2\bin\javac.exe" -encoding UTF-8 -cp build\classes -d build\test-classes test\com\hdsl\*.java
    & "C:\Program Files\Java\jdk-25.0.2\bin\java.exe" -cp build\classes;build\test-classes com.hdsl.SidebarLayoutSmokeTest

## Usage

1. Launch HDSL and create an instance (name + Harness version) in the instance library
2. Select the instance and start it: HDSL installs that dsh version into runtimes/<version> and initializes a dedicated instances/<id>/workspace and dsh-home
3. While running, the main UI shows live status and the loopback port; stopping terminates the whole process tree
4. The Plugins page lets you browse / search / filter plugins; the cache refreshes automatically after add / update / remove

## FAQ

| Issue | Answer |
|---|---|
| port occupied | The port is held by an unverified process. HDSL never kills it; end that process manually and retry |
| Stale plugin list | Ordinary navigation reads the instant cache; background refresh runs on startup, instance switch, plugin changes, instance launch, and manual refresh |
| Hide official plugins | Use the Hide official plugins toggle on the Plugins page — view-only, does not affect the cache or installs |
| Do I need Java installed? | No. Packaged builds bundle the runtime; JDK 25 is only needed to build from source |
| How do I upgrade? | Download the new version ZIP and overwrite the old directory; config / data / instances are preserved |
| Uninstall | Delete the program directory — nothing is written to the system registry |

## Repository layout

- src/com/hdsl/Launcher.java — single-file desktop client (UI, instances, runtimes, plugins, process control)
- test/com/hdsl/ — smoke and lifecycle integration tests
- assets/ — application icon and hero banner
- VERSION — release version

## Changelog

### v0.1 (2026-08-17) — first public source release

Corresponds to internal build 0.6.0:

- Unique loopback port per instance, with automatic migration of legacy records
- Runtime state polling that never blocks the UI; PID + port + workspace ownership verification
- Full process-tree termination on stop (Windows / Linux)
- Instant plugin cache with background refresh
- Plugin search and official-plugin filter
- Self-contained, version-isolated portable architecture (since internal 0.5.0)

## Community & feedback

- Issues and suggestions are welcome
- This repository is tagged with the dsh-plugin topic and can be found on the [GitHub dsh-plugin topic page](https://github.com/topics/dsh-plugin)

## License

This project is licensed under the MIT License (see the LICENSE file). Copyright (c) 2026 jiefing.

