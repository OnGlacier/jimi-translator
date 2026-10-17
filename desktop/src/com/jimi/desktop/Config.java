package com.jimi.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/** 配置：存在 ~/.jimi-translator/config.properties，纯文本，方便手改。 */
final class Config {

    static final String DEFAULT_BASE = "https://api.deepseek.com/v1";
    static final String DEFAULT_MODEL = "deepseek-flash";

    String apiKey = "";
    String baseUrl = DEFAULT_BASE;
    String model = DEFAULT_MODEL;
    int intervalMs = 8000;
    /** auto / en2zh / zh2en */
    String direction = "auto";
    boolean stream = true;
    boolean think = false;
    boolean bilingual = true;
    int alphaPct = 86;
    String systemPrompt = "";
    String glossary = "";

    static Path dir() {
        String home = System.getProperty("user.home", ".");
        return Paths.get(home, ".jimi-translator");
    }

    static Path file() {
        return dir().resolve("config.properties");
    }

    static Config load() {
        Config config = new Config();
        Path path = file();
        if (!Files.exists(path)) return config;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException error) {
            return config;
        }
        config.apiKey = props.getProperty("apiKey", config.apiKey);
        config.baseUrl = props.getProperty("baseUrl", config.baseUrl);
        config.model = props.getProperty("model", config.model);
        config.intervalMs = parseInt(props.getProperty("intervalMs"), config.intervalMs);
        config.direction = props.getProperty("direction", config.direction);
        config.stream = parseBool(props.getProperty("stream"), config.stream);
        config.think = parseBool(props.getProperty("think"), config.think);
        config.bilingual = parseBool(props.getProperty("bilingual"), config.bilingual);
        config.alphaPct = parseInt(props.getProperty("alphaPct"), config.alphaPct);
        config.systemPrompt = props.getProperty("systemPrompt", "");
        config.glossary = props.getProperty("glossary", "");
        return config;
    }

    void save() {
        Properties props = new Properties();
        props.setProperty("apiKey", apiKey);
        props.setProperty("baseUrl", baseUrl);
        props.setProperty("model", model);
        props.setProperty("intervalMs", String.valueOf(intervalMs));
        props.setProperty("direction", direction);
        props.setProperty("stream", String.valueOf(stream));
        props.setProperty("think", String.valueOf(think));
        props.setProperty("bilingual", String.valueOf(bilingual));
        props.setProperty("alphaPct", String.valueOf(alphaPct));
        props.setProperty("systemPrompt", systemPrompt == null ? "" : systemPrompt);
        props.setProperty("glossary", glossary == null ? "" : glossary);
        try {
            Files.createDirectories(dir());
            try (OutputStream out = Files.newOutputStream(file())) {
                props.store(out, "Jimi Translator desktop config");
            }
        } catch (IOException ignored) {
        }
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Throwable error) {
            return fallback;
        }
    }

    private static boolean parseBool(String raw, boolean fallback) {
        if (raw == null) return fallback;
        return Boolean.parseBoolean(raw.trim());
    }
}
