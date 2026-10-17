# 基米翻译 · 桌面版（Windows / Linux）

同一套「读屏幕 → 视觉模型 → 去重 → 浮窗」逻辑的桌面实现，纯 Java 17 + Swing，**零第三方依赖**。

```
desktop/
├── src/com/jimi/desktop/
│   ├── Main.java            入口（--selftest 跑自检）
│   ├── Settings.java        设置窗口（橙色哈基米主题）
│   ├── Overlay.java         悬浮译文卡：可拖动、可收起成猫猫圆钮
│   ├── RegionPicker.java    全屏框选（拖矩形，Esc 取消）
│   ├── Controller.java      截屏 / 定时 / 翻译 / 去重 的调度
│   ├── Screen.java          截屏、缩放、JPEG、画面指纹
│   ├── TranslateClient.java 模型调用（流式 / 整段、JSON 抢救）
│   ├── Prompt.java          提示词（双向 / 英译中 / 中译英）
│   ├── Dedupe.java          句子级去重（归一化 + Jaccard）
│   ├── Config.java          ~/.jimi-translator/config.properties
│   └── Json.java            极简 JSON 解析
├── build.sh / run.sh        Linux / macOS
└── build.bat / run.bat      Windows
```

## 安装（从 Release 下载，不用装 Java）

| 平台 | 文件 | 用法 |
|---|---|---|
| Windows | `jimi-translator-<版本>-windows.exe` | 双击安装，自带运行时；未签名，SmartScreen 点「更多信息 → 仍要运行」 |
| Linux | `jimi-translator_<版本>-1_amd64.deb` | `sudo apt install ./jimi-translator_xxx.deb` |
| 任意（需 Java 17+） | `jimi-translator-desktop-<版本>.jar` | `java -jar jimi-translator-desktop-xxx.jar` |

deb 在 **Ubuntu 22.04** 上构建：依赖写的是 `libasound2`（24.04 上构建会写成 `libasound2t64`，22.04 装不上），
所以 Ubuntu 22.04 / 24.04、Debian 12+ 都能装；更老的发行版请用 jar 或自行构建。

## 从源码运行

需要 **JDK 17 或更高**。

```bash
# Linux / macOS
./build.sh && ./run.sh
```

```bat
:: Windows
build.bat
run.bat
```

首次启动会提示填 API key：默认接口 `https://api.deepseek.com/v1`、模型 `deepseek-flash`（需要支持图片输入）。

## 用法

1. 填好 API key →「保存设置」→「显示 / 收回浮窗」；
2. 浮窗上点「**框选**」，拖一个矩形（松开即翻，Esc 取消）；
3. 点「**自动**」开始定时跟读，画面没变化时会跳过，不浪费 token；
4. 动作行：`框选 · 自动 · 双向/英译中/中译英 · 不透明度 · 清空 · 收起`；
   「不透明度」展开背景滑杆和「显示原文」；
5. 「收起」后是一颗可拖动的猫猫圆钮，点它展开。

### 窗口大小与位置

- **拖动**：按住标题文字、状态文字或标题栏空白处都能拖；
- **缩放**：拖右下角的 ◢（最小 360×240）；
- **全屏**：点标题栏右侧的 **⛶**，再点一次还原；**按 Esc 也能退出全屏**；
- 译文多了会自动滚动到最新一条。

### 出问题怎么办

桌面版会把未捕获异常写到 `~/.jimi-translator/crash.log`。
遇到闪退 / 卡住，把这个文件发到 issue 里，能直接定位。

## 和手机版的差别

| | 手机版 | 桌面版 |
|---|---|---|
| 截屏 | 无障碍服务 `takeScreenshot` | `java.awt.Robot` |
| 悬浮窗 | `TYPE_ACCESSIBILITY_OVERLAY` | 无边框 + 置顶 `JFrame` |
| 后台常驻 | 无障碍服务 | 不常驻，关窗口即退出 |
| 快捷键 | 无 | 无（点浮窗按钮） |

桌面平台没有「无障碍服务被系统关掉」这个问题——`Robot` 截屏不需要额外授权（Wayland 下需要 XWayland 或门户授权，见下）。

## 平台备注

- **Windows**：开箱即用，`Robot` 截屏、透明窗口都支持；
- **Linux X11**：开箱即用；
- **Linux Wayland**：`Robot` 截屏可能只能拿到黑屏，需要：
  - 用 XWayland 会话运行，或
  - 在 GNOME/KDE 里给 Java 授予屏幕录制门户权限（`xdg-desktop-portal`），或
  - 改用 X11 会话登录。
- 多显示器：截屏取主屏，摆位按主屏坐标。

## 自检

无图形环境也能跑（CI 可用）：

```bash
java -jar build/jimi-translator.jar --selftest
```

输出 `SELFTEST_OK` 表示 JSON 解析、截断抢救、去重、提示词拼接都正常。
