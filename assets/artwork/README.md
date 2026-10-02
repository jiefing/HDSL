# HDSL 图片生成记录

日期：2026-10-02。版本：0.2.0-preview.3。生成记录 revision：2。

以下六张原图由 OpenAI 内置 `image_gen` 工具生成。最终形象按用户提供的两张参考修正为深蓝至浅蓝渐变的长卷发、白色褶边发饰与蓝蝴蝶结、蓝白女仆裙、侧向鲸鳍和大鲸尾；图标使用方块感的 HDSL 字标。此前短发水手服草稿已替换。

## 素材清单

路径相对于源码根目录；尺寸为最终 PNG 的实际尺寸。

| 用途 / 名称 | 文件 | 尺寸 | 透明度 |
| --- | --- | --- | --- |
| HDSL Q 版形象与图标原图 | `assets/hdsl-whale-icon.png` | 1254 × 1254 | RGBA，含透明背景 |
| 01 晴空招手 | `src/main/resources/com/hdsl/ui/backgrounds/whale-01.png` | 1672 × 941 | RGB，不透明 |
| 02 浅海微风 | `src/main/resources/com/hdsl/ui/backgrounds/whale-02.png` | 1672 × 941 | RGB，不透明 |
| 03 暖阳小憩 | `src/main/resources/com/hdsl/ui/backgrounds/whale-03.png` | 1672 × 941 | RGB，不透明 |
| 04 月色晚安 | `src/main/resources/com/hdsl/ui/backgrounds/whale-04.png` | 1672 × 941 | RGB，不透明 |
| 05 初雪围巾 | `src/main/resources/com/hdsl/ui/backgrounds/whale-05.png` | 1672 × 941 | RGB，不透明 |

设置以 `builtin:whale-01` 至 `builtin:whale-05` 保存内置背景选择；空值使用 01，已有本地图片路径继续有效。

## 参考与生成关系

完整提示词、初始场景提示和最终修正提示保存在 [generation-prompts.json](generation-prompts.json)。最终生成使用的用户附件为：

- `codex-clipboard-e4efdc49-08c7-466a-8cb0-ca90695d6b28.png`
- `codex-clipboard-59d3e8d8-93af-4ef5-867f-cb3cf953518f.png`

图标结合这两张人物参考和先前生成图标的构图重新生成。五张背景保留初稿各自的场景与动作，再结合用户人物参考和修正后的 HDSL 图标统一角色。参考图中的说明文字未纳入输出。

前期参考页：[AI 耕图教程页](https://aigengtu.com/tutorial)、[VGO 新闻页](https://www.vgover.com/news/227900)。网页用于研究通用形象元素，没有下载网页原图用作生成输入；最终人物依据上述用户附件修正。

## 应用中的派生文件

- `src/main/resources/com/hdsl/ui/icons/hdsl.png` 是图标原图的逐字节副本，用于界面头像与窗口图标。
- `assets/HDSL.ico` 由 `scripts/build-icon.ps1` 从图标原图转换，包含 16、24、32、48、64、128、256 像素尺寸；转换只做缩放与格式导出。
- 便携包的对应源码位于 `sources/hdsl-0.2.0-preview.5-source.zip`，其中包含本记录、素材及转换脚本。

这些具体图片是本轮为 HDSL 生成的素材，不是 HMCL 资产或 DeepSeek 官方形象。

These six images were AI-generated with the built-in `image_gen` tool and corrected using the user's two character references. The prompt record preserves both the initial scenes and the final edits. The in-app icon is an exact PNG copy; the Windows ICO is a resized format conversion. This is HDSL artwork, not HMCL artwork or an official DeepSeek character.
