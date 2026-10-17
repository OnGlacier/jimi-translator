# 架构

## 组件

```
MainActivity          配置页（橙色主题）：key / 端点 / 模型 / 方向 / 提示词 / 术语 / 开关
CaptureService        无障碍服务：截屏、调度、请求模型、去重、写浮窗
Overlay               悬浮窗：卡片、按钮、译文流、透明度、显示原文、收起圆钮
RegionPicker          全屏框选层：拖一个矩形，返回屏幕坐标
Translator            模型调用：OpenAI 兼容 /chat/completions（流式 / 非流式）、JSON 抢救
Dedupe                句子级去重：归一化 key + 词集合 Jaccard，持久化到 SharedPreferences
Prompt                提示词（按翻译方向生成）与术语表
Store                 SharedPreferences 封装
```

全部 Java 8、零第三方依赖，UI 用代码构建（无 XML 布局），因此可以只用 `aapt2 + javac + d8` 打包。

## 一次「整屏翻译」的完整流程

1. **触发**：点「自动」（每 N 秒）或手动框选后的回调；
2. **截屏**：`AccessibilityService.takeScreenshot(Display.DEFAULT_DISPLAY, …)` → `HardwareBuffer` → `Bitmap`；
3. **画面指纹**：缩到 32×24 灰度，与上一帧逐像素平均差 < 1.6 视为「没动」，直接跳过（手动触发不跳过）；
4. **准备图像**：
   - 整屏模式：把原图缩到宽 1500、JPEG q80；
   - 框选模式：裁出选区（外扩 16dp）作为主图（宽 1400、q88），整屏图作上下文一并发送；
5. **请求模型**：带「已翻译句子」清单、系统提示词、自定义术语；
6. **流式解析**：逐行读 SSE，累计 `delta.content`，用正则从累计文本里捞已闭合的 `{en,zh}` 对，凑齐就回调显示；
7. **落库**：按 `i` 排序，逐段 `Dedupe.isDuplicate` → `remember` → 写浮窗；
8. **收尾**：更新状态（新增 N 段 / 本屏已翻完 / 失败原因），保存去重表。

## 去重

```java
key  = 小写、去连字符、去标点、合并空白
word = 长度 ≥ 3 且非停用词的词集合
重复条件：key 完全相等 或 Jaccard(word) ≥ 0.72
```

为什么用 Jaccard：PDF 滚动时同一段可能被裁掉前半句，两段文字不完全相同但词集合重合度高，直接判重可以避免重复翻译。

去重表上限 300 条，随 `SharedPreferences` 持久化；「清空」会同时清掉内存表、持久化文件、画面指纹和计数。

## 悬浮窗

- 类型：`TYPE_ACCESSIBILITY_OVERLAY`（无障碍服务附加的悬浮层，不需要 `SYSTEM_ALERT_WINDOW`）；
- 拖动：标题栏 / 收起圆钮自己处理 `onTouchEvent`，用 `WindowManager.updateViewLayout` 跟随手指；
- 收起：卡片移除，换成 56dp 猫猫圆钮，位置持久化（`bubbleX/bubbleY`），可拖动；
- 按钮分工：动作栏是操作（框选 / 自动 / 方向 / 清空 / 收起），折叠行是显示设置（不透明度 / 显示原文）；
- 方向按钮点一下循环「双向 → 英译中 → 中译英」，立即生效；
- 配色：**背景不透明度 < 55% 用近黑字，≥ 55% 用近白字，且背景越透笔画越粗**（`Paint.FILL_AND_STROKE` + 动态 `strokeWidth`），避免中间灰导致的低对比度。

## 错误处理

| 现象 | 处理 |
|---|---|
| `takeScreenshot` 返回错误码 | 翻译成中文提示（太频繁 / 无权限 / 安全窗口），不打断自动模式 |
| 模型返回非法 JSON | 用正则抢救完整段落；仍然为空才把模型原话贴进浮窗 |
| 模型回 `{"segments":[]}` | 视为「本屏已翻完」，不显示调试信息 |
| 网关不支持 `response_format` / `thinking` | 自动降级重试（去掉字段再发一次） |


## 已知限制

- **无障碍服务被 ROM 关掉后无法自动恢复**：Android 不允许 App 自己打开自己的无障碍服务。
  唯一的合规路径是先拿到 `WRITE_SECURE_SETTINGS`（Shizuku / ADB / root 一次性授权），
  再写回 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`。本项目为保持轻量没有内置，
  历史版本 `v1.38` 有参考实现（`ShizukuHelper` + `A11y`，当时因启动崩溃回退）。

## 数据与隐私

- API key、提示词、术语、去重表全部存在 App 私有 `SharedPreferences`；
- 截图只在本机内存里处理，**只把截图和提示词发给你自己配置的模型接口**；
- 没有任何自建服务器、统计上报或账号系统。
