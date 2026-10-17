package com.jimi.desktop;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 调 OpenAI 兼容接口：支持流式、JSON 抢救、失败降级重试。 */
final class TranslateClient {

    static final class Segment {
        final String src;
        final String dst;
        final int order;

        Segment(String src, String dst, int order) {
            this.src = src;
            this.dst = dst;
            this.order = order;
        }
    }

    interface Listener {
        void onSegment(Segment segment);

        void onStatus(String text);
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build();

    private TranslateClient() {}

    static List<Segment> translate(Config config, byte[] fullJpeg, byte[] regionJpeg,
                                   List<String> known, String direction, Listener listener) throws Exception {
        String system = Prompt.system(config.systemPrompt);
        String user = Prompt.user(direction, known, config.glossary, regionJpeg != null);
        String body = buildBody(config, system, user, fullJpeg, regionJpeg);
        String url = endpoint(config.baseUrl);

        if (config.stream) {
            try {
                return stream(config.apiKey, url, body, listener);
            } catch (IOException error) {
                // 有些网关不接受 stream + response_format，退回一次性请求
                if (listener != null) listener.onStatus("流式失败，改用整段模式");
                return once(config.apiKey, url, body);
            }
        }
        return once(config.apiKey, url, body);
    }

    private static String endpoint(String base) {
        String trimmed = base == null ? Config.DEFAULT_BASE : base.trim();
        if (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.endsWith("/chat/completions")) return trimmed;
        return trimmed + "/chat/completions";
    }

    private static String buildBody(Config config, String system, String user,
                                    byte[] fullJpeg, byte[] regionJpeg) {
        StringBuilder sb = new StringBuilder(1024 + fullJpeg.length + (regionJpeg == null ? 0 : regionJpeg.length));
        sb.append("{\"model\":\"").append(Json.escape(config.model)).append("\",");
        sb.append("\"max_tokens\":16384,");
        sb.append("\"stream\":").append(config.stream).append(',');
        sb.append("\"thinking\":{\"type\":\"").append(config.think ? "enabled" : "disabled").append("\"},");
        if (!config.think) sb.append("\"response_format\":{\"type\":\"json_object\"},");
        sb.append("\"messages\":[");
        sb.append("{\"role\":\"system\",\"content\":\"").append(Json.escape(system)).append("\"},");
        sb.append("{\"role\":\"user\",\"content\":[");
        sb.append("{\"type\":\"text\",\"text\":\"").append(Json.escape(user)).append("\"}");
        if (regionJpeg != null) {
            sb.append(",{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/jpeg;base64,")
                .append(Base64.getEncoder().encodeToString(regionJpeg)).append("\"}}");
        }
        sb.append(",{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/jpeg;base64,")
            .append(Base64.getEncoder().encodeToString(fullJpeg)).append("\"}}");
        sb.append("]}]}");
        return sb.toString();
    }

    // ---------------------------------------------------------------- 流式

    private static List<Segment> stream(String apiKey, String url, String body, Listener listener) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(3))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpResponse<InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            String error = readAll(response.body());
            throw new IOException("HTTP " + response.statusCode() + ": " + shorten(error));
        }
        StringBuilder content = new StringBuilder();
        Set<String> emitted = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
                Map<String, Object> chunk = Json.asObject(Json.parse(payload));
                if (chunk == null) continue;
                List<Object> choices = Json.asArray(chunk.get("choices"));
                if (choices == null || choices.isEmpty()) continue;
                Map<String, Object> choice = Json.asObject(choices.get(0));
                if (choice == null) continue;
                Map<String, Object> delta = Json.asObject(choice.get("delta"));
                if (delta == null) continue;
                String piece = Json.asString(delta.get("content"));
                if (piece == null || piece.isEmpty()) continue;
                content.append(piece);
                for (Segment segment : salvage(content.toString())) {
                    String key = segment.order + "\u0000" + segment.src;
                    if (emitted.add(key) && listener != null) listener.onSegment(segment);
                }
            }
        }
        List<Segment> finalList = parseStrict(content.toString());
        if (finalList.isEmpty()) finalList = salvage(content.toString());
        return dedupeBySrc(finalList);
    }

    // ---------------------------------------------------------------- 非流式

    private static List<Segment> once(String apiKey, String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(3))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + ": " + shorten(response.body()));
        }
        Map<String, Object> root = Json.asObject(Json.parse(response.body()));
        if (root == null) throw new IOException("返回不是 JSON");
        List<Object> choices = Json.asArray(root.get("choices"));
        if (choices == null || choices.isEmpty()) throw new IOException("没有 choices");
        Map<String, Object> choice = Json.asObject(choices.get(0));
        Map<String, Object> message = choice == null ? null : Json.asObject(choice.get("message"));
        String content = message == null ? null : Json.asString(message.get("content"));
        if (content == null || content.trim().isEmpty()) {
            String reasoning = message == null ? null : Json.asString(message.get("reasoning_content"));
            if (reasoning != null && !reasoning.trim().isEmpty()) {
                throw new IOException("模型只回了思考内容，检查是否该关闭思考模式");
            }
            throw new IOException("模型返回空内容");
        }
        List<Segment> segments = parseStrict(content);
        if (segments.isEmpty()) segments = salvage(content);
        return dedupeBySrc(segments);
    }

    // ---------------------------------------------------------------- 解析

    private static List<Segment> parseStrict(String text) {
        String cleaned = stripFences(text);
        Map<String, Object> root = Json.asObject(Json.parse(cleaned));
        if (root == null) return new ArrayList<>();
        List<Object> items = Json.asArray(root.get("segments"));
        if (items == null) return new ArrayList<>();
        List<Segment> result = new ArrayList<>();
        for (Object raw : items) {
            Map<String, Object> item = Json.asObject(raw);
            if (item == null) continue;
            String src = firstString(item, "src", "en");
            String dst = firstString(item, "dst", "zh");
            if (src == null || dst == null) continue;
            src = src.trim();
            dst = dst.trim();
            if (src.isEmpty() || dst.isEmpty()) continue;
            result.add(new Segment(src, dst, (int) parseNumber(item.get("i"))));
        }
        return result;
    }

    private static String firstString(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            String value = Json.asString(map.get(key));
            if (value != null) return value;
        }
        return null;
    }

    private static double parseNumber(Object value) {
        return value instanceof Double ? (Double) value : 0;
    }

    /** 从半截 JSON / 流式分片里把已经闭合的 {src,dst} 捞出来。 */
    static List<Segment> salvage(String text) {
        List<Segment> result = new ArrayList<>();
        if (text == null) return result;
        String cleaned = stripFences(text);
        java.util.Deque<Integer> stack = new java.util.ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                stack.push(i);
            } else if (c == '}' && !stack.isEmpty()) {
                int from = stack.pop();
                String block = cleaned.substring(from, i + 1);
                Map<String, Object> item = Json.asObject(Json.parse(block));
                if (item != null) {
                    String src = firstString(item, "src", "en");
                    String dst = firstString(item, "dst", "zh");
                    if (src != null && dst != null && !src.trim().isEmpty() && !dst.trim().isEmpty()) {
                        result.add(new Segment(src.trim(), dst.trim(), (int) parseNumber(item.get("i"))));
                    }
                }
            }
        }
        return result;
    }

    private static List<Segment> dedupeBySrc(List<Segment> segments) {
        List<Segment> ordered = new ArrayList<>(segments);
        ordered.sort((a, b) -> a.order == 0 || b.order == 0 ? 0 : Integer.compare(a.order, b.order));
        List<Segment> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Segment segment : ordered) {
            String key = Dedupe.normalize(segment.src);
            if (key.isEmpty() || !seen.add(key)) continue;
            result.add(segment);
        }
        return result;
    }

    private static String stripFences(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstBreak = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstBreak > 0 && lastFence > firstBreak) {
                trimmed = trimmed.substring(firstBreak + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private static String readAll(InputStream stream) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            int total = 0;
            while ((read = stream.read(chunk)) > 0 && total < 64 * 1024) {
                buffer.write(chunk, 0, read);
                total += read;
            }
            return buffer.toString(StandardCharsets.UTF_8.name());
        } catch (IOException error) {
            return "";
        }
    }

    private static String shorten(String text) {
        if (text == null) return "";
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 240 ? oneLine.substring(0, 240) + "…" : oneLine;
    }
}
