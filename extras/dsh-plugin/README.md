# dsh-plugin（早期原型）

这是同一套能力的 **DeepSeek Harness 网页插件**版本：在 DSH 的 Web UI 里注册一个浮层面板，读取平板屏幕（通过 DSHA 的 `/app/ui/screenshot`）并翻译。

**依赖**：需要 [DeepSeek Harness](https://deepseek-harness.github.io/deepseek-harness/) + DSHA（Android 端 3090 桥），无法脱离这两个运行。保留在这里是为了：

- 记录最初的产品形态（网页浮层 vs 独立 App）；
- 其中 `page.js` 是一个极简的独立翻译页，可以被任何浏览器打开（`/rl-translator`），适合分屏/浮窗场景；
- 提示词、去重、流式处理逻辑与 App 版本同源，可以互相参照。

## 结构

| 文件 | 作用 |
|---|---|
| `index.js` | Host 半部：HTTP 路由、截屏、模型调用、去重、悬浮条推送 |
| `client.js` | 浏览器半部：`shell.overlay` 浮层面板 |
| `page.js` | 独立翻译页（无聊天界面，纯译文流） |
| `cordis.patch.yml` | DSH 插件补丁 |

## 安装

把本目录放进 DSH 的插件搜索路径，用插件管理器安装后启用；或在 `cordis.patch.yml` 中插入：

```yaml
- insert:
    - id: dsh-rl-translator
      name: 'dsh-rl-translator'
```

> 本项目的主要交付是根目录的 Android 应用，本目录不保证持续维护。
