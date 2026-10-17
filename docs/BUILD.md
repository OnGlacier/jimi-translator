# 构建说明

不需要 Gradle / Android Studio，只用 SDK 命令行工具手工打包。

## 依赖

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | `javac` 以 `-source 8 -target 8` 编译 |
| Android SDK Platform | android-35 | 提供 `android.jar` |
| Android SDK Build-Tools | 34.0.0 | `aapt2`、`d8`、`apksigner`、`zipalign` |
| sh + python3 | 任意 | 打包 dex 进 APK 时用 python 重写 zip |

> 在 **arm64 Linux**（比如 Android 手机里的容器）上构建时，官方 `aapt2`/`zipalign` 是 x86_64 二进制，需要用 `qemu-user-static` 跑：
>
> ```bash
> qemu-x86_64-static -L /path/to/x86_64-rootfs $ANDROID_HOME/build-tools/34.0.0/aapt2 ...
> ```
>
> `build.sh` 里的 `QEMU` / `X86ROOT` 两个变量可以改；x86_64 主机上直接留空即可。

## 命令

```bash
export ANDROID_HOME=$HOME/Android/Sdk
bash app/build.sh
```

脚本依次做：

1. `aapt2 compile` 把 `res/` 编译成 `res.zip`；
2. `aapt2 link` 生成只含资源的 `base.apk`（自动生成二进制 AndroidManifest）；
3. `javac` 编译 `src/`；
4. `d8` 把 class 转成 `classes.dex`（`--min-api 30`）；
5. 用 python 把 `classes.dex` 塞进 `base.apk`，同时保证 `resources.arsc` 的 4 字节对齐；
6. `apksigner` 用 `keystore.jks` 做 v2 签名（首次运行会自动 `keytool` 生成）。

产物：`app/out/app.apk`。

## 签名

`keystore.jks` **不要提交**（已在 `.gitignore` 里）。首次构建会自动生成：

```
storepass / keypass: rltranslate
```

正式发布请换成自己的密钥，并保管好——换钥匙会导致无法覆盖升级。

## 内存吃紧时

`d8` 和 `javac` 各需要 100–200 MB。内存不足时（Android 的 LMKD 会杀进程，表现为 `Killed`）可以：

- 关掉后台应用 / 停掉正在跑的翻译；
- 减小 JVM 堆：`java -Xmx128m ... com.android.tools.r8.D8 ...`；
- 分批重试，脚本里的每一步都可以单独跑（见上文 1–6）。
