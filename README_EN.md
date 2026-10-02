# HDSL — Hello DeepSeek Harness Launcher

**HDSL 0.2.0**

[Download the Windows x64 portable package and source](https://github.com/jiefing/HDSL/releases/tag/v0.2.0). Extract the archive and run `HDSL.exe`; keep the full directory together.

A Windows launcher with a JavaFX interface ported and adapted from HMCL, isolated Harness instances, exact runtime versions, plugin management, task logs, and PackForge/Overture pack import. The port includes the window frame, sidebar, list controls, and home launch area.

- The sidebar groups accounts, instances, and general tools. API Key accounts are encrypted with Windows DPAPI for the current user and can be assigned to HDSL instances. Saving an account does not test the API or send a billable request; changes take effect on the next launch.
- Runtime support is based on actual CLI help and local capability declarations. Breaking changes in future Harness or Node versions may still require updates.
- Changing a runtime version copies the selected profile into a new instance and retains the original.
- Pack export is for sharing. Credentials, sessions, private settings, and patch configuration are excluded; they must be configured again after import.
- External plugin and pack install scripts are disabled by default.
- Opening Create Instance refreshes the Harness version list in the background. You can continue typing or creating the instance; a failed refresh retains your input and the known versions.
- Choose from five built-in whale-girl backgrounds in Settings, or keep a custom local image. A matching chibi avatar and HDSL icon are included.
- Search the [dsh-plugin topic](https://github.com/topics/dsh-plugin), then check a project's declared packages against npm publications. Only confirmed targets receive an install action; other repositories remain view-only. This metadata check does not test plugin functionality.
- Query Windows x64 desktop installers from the [official DeepSeek download page](https://www.deepseek.com/en/download/) by default, with two clearly named community sources also available. Downloads show byte progress and verify a published SHA-256 when available. Open the download folder and run the installer yourself.

Run `HDSL.exe` from its complete portable folder. Configure a proxy such as `http://127.0.0.1:7890` if needed, open Create Instance, select or enter an exact Harness version, and launch it.

HDSL account binding uses temporary launch settings for supported Harness structures. Unknown structures produce a clear error. Independent desktop applications manage their own accounts and plugins; HDSL does not inject accounts into them.

In Settings → Appearance, choose Clear Sky, Sea Breeze, Warm Afternoon, Moonlight, or First Snow, then save. Clear Sky is the default; existing custom image paths are retained. The new artwork was created with the built-in image generation tool. See the [artwork record](assets/artwork/README.md) for the files, prompts, and references.

Build with JDK 21+:

```powershell
.\scripts\build.ps1
.\scripts\build.ps1 -Package
```

Or run `mvn -B -ntp verify`. Real network and UI tests are opt-in. CI does not publish releases.

The portable package includes corresponding source and build scripts at `sources/hdsl-0.2.0-source.zip`.

See the [Chinese guide](docs/USER_GUIDE.md), [accounts and downloads](docs/accounts-and-downloads.md), [validation record](docs/VALIDATION.md), [runtime compatibility](docs/runtime-compatibility.md), [pack support](docs/pack-support.md), and [third-party notices](THIRD_PARTY_NOTICES.md).

This version includes HMCL-derived code and is distributed under GPLv3 or later with HMCL's additional terms. The original HDSL MIT notice is retained. See the [port and source record](docs/HMCL_PORT.md) for the upstream revision and adaptations. Upstream attribution remains visible in the application. No Overture source code is reused. HDSL is not affiliated with DeepSeek or HMCL.
