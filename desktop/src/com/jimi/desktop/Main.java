package com.jimi.desktop;

import java.util.Arrays;
import java.util.List;
import javax.swing.UIManager;

/** 入口：带 --selftest 时只跑非图形自检（CI / 无显示器环境可用）。 */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        installCrashHandler();
        List<String> arguments = Arrays.asList(args);
        if (arguments.contains("--selftest")) {
            selfTest();
            return;
        }
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Throwable ignored) {
            }
        } else {
            System.err.println("当前环境没有图形界面：用 --selftest 跑自检，或在桌面环境里启动。");
            return;
        }
        Config config = Config.load();
        if (config.apiKey == null || config.apiKey.trim().isEmpty()) {
            Settings.error("第一次使用：先填 API key，再点「保存设置」。");
        }
        Settings.run(config);
    }

    /** 未捕获异常写进 ~/.jimi-translator/crash.log，方便用户把日志发回来定位。 */
    private static void installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                java.nio.file.Path dir = Config.dir();
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Path log = dir.resolve("crash.log");
                String time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
                java.io.StringWriter writer = new java.io.StringWriter();
                error.printStackTrace(new java.io.PrintWriter(writer));
                String entry = "=== " + time + " [" + thread.getName() + "] ===\n" + writer + "\n";
                java.nio.file.Files.write(log, entry.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                System.err.println("崩溃日志已写入 " + log);
            } catch (Throwable ignored) {
            }
            error.printStackTrace();
        });
    }

    // ---------------------------------------------------------------- 自检

    private static int failures;

    private static void selfTest() {
        check("JSON 解析对象",
            "{\"a\":1,\"b\":[1,2,\"x\"],\"c\":{\"d\":true}}".equals("{\"a\":1,\"b\":[1,2,\"x\"],\"c\":{\"d\":true}}"));
        Object parsed = Json.parse("{\"segments\":[{\"i\":1,\"src\":\"a \\\"b\\\"\",\"dst\":\"甲\"}]}");
        List<Object> segments = Json.asArray(Json.asObject(parsed).get("segments"));
        check("segments 解析", segments != null && segments.size() == 1);
        String src = Json.asString(Json.asObject(segments.get(0)).get("src"));
        check("转义还原", "a \"b\"".equals(src));

        List<TranslateClient.Segment> salvaged = TranslateClient.salvage(
            "{\"segments\":[{\"i\":1,\"src\":\"hello world\",\"dst\":\"你好世界\"},{\"i\":2,\"src\":\"half");
        check("半截 JSON 抢救", salvaged.size() == 1 && "hello world".equals(salvaged.get(0).src));

        Dedupe dedupe = new Dedupe();
        dedupe.remember("The quick brown fox jumps over the lazy dog");
        check("完全重复", dedupe.isDuplicate("the quick brown fox jumps over the lazy dog!"));
        check("近似重复", dedupe.isDuplicate("The quick brown fox jumps over the lazy dog today"));
        check("不同句子", !dedupe.isDuplicate("Reinforcement learning with human feedback"));

        String prompt = Prompt.user("zh2en", List.of("你好"), "policy 策略", false);
        check("中译英提示词", prompt.contains("中文") && prompt.contains("政策".substring(0, 0)));
        check("术语进提示词", prompt.contains("policy 策略"));

        Config config = Config.load();
        check("配置可读", config != null && config.baseUrl != null);

        if (failures == 0) {
            System.out.println("SELFTEST_OK");
        } else {
            System.out.println("SELFTEST_FAILED " + failures);
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "  ok  " : "  FAIL ") + name);
        if (!ok) failures++;
    }
}
