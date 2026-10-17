# 贡献指南

## 提交前

1. 跑通构建（见 [docs/BUILD.md](docs/BUILD.md)）：

   ```bash
   export ANDROID_HOME=/path/to/android-sdk
   bash scripts/build-apk.sh
   ```

   看到 `BUILD_OK` 和 `build/app.apk` 才算过。

2. 真机自测（如果改的是读屏/悬浮窗）：
   - 装 APK → 开无障碍服务 → 框选一段英文 → 能出中文；
   - 收起 → 出现 🐱 圆钮 → 能拖动、能点开；
   - 切到别的 App，浮窗依然可用。

## 代码约定

- Java 8 语法（`source/target 1.8`），不引入第三方库——整个 APK 约 70 KB，保持住。
- UI 全部用代码构建（没有 XML 布局），便于手写构建脚本直接 `aapt2 link`。
- 不使用 `R` 类：资源用 `getResources().getIdentifier("name", "drawable", getPackageName())` 引用（手写构建不生成 R）。
- 新增可见文案时，中文优先，保持短句、口语化。
- 悬浮窗是主要阅读界面：**改配色可以，别改布局和交互**（会损体验）。
- 按钮命名保持一义一词：`框选`（选区）、`自动`（连续翻译）、`双向/英译中/中译英`（方向）、
  `清空`（重置）、`收起`（缩成圆钮）、`不透明度`（背景深浅）；不要再用「中英」「跟读」这类会串味的词。

## 提交信息

```
<area>: <一句话说明>

- 细节 1
- 细节 2
```

`area` 取值：`app`、`overlay`（悬浮窗）、`prompt`（提示词）、`docs`、`dsh-plugin`。

## 关于 AI 协作

本项目是「人类提需求 + AI 结对实现」的产物。AI 参与的提交会在尾部标注：

```
Co-authored-by: JiMi AI <ai@jimi-translator.local>
```

欢迎继续用 AI 工具提 PR，但请自己跑过构建并真机验证——人类为提交负责。
