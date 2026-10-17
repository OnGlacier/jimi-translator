package com.rltranslator.overlay;

import android.graphics.Bitmap;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** OpenAI 兼容的 chat/completions 调用：一张截图进去，一批 {en,zh} 出来。 */
final class Translator {

    static final class Segment {
        final String en;
        final String zh;
        /** 模型给的段序（1 起，从上到下）；0 表示没给。 */
        final int order;

        Segment(String en, String zh) {
            this(en, zh, 0);
        }

        Segment(String en, String zh, int order) {
            this.en = en;
            this.zh = zh;
            this.order = order;
        }
    }

    static final class Result {
        final List<Segment> segments = new ArrayList<>();
        String error;
        /** 模型原始回复（调试用：只有解析失败时才贴出来）。 */
        String raw = "";
        /** 回复是不是合法 JSON（哪怕 segments 为空）。true 表示「本屏确实没有新内容」。 */
        boolean parsed;
    }

    private static final class Reply {
        final int code;
        final String body;

        Reply(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private Translator() {}

    /** 缩到合适宽度再压 JPEG：省流量、省视觉 token。 */
    static byte[] toJpeg(Bitmap source, int maxWidth, int quality) {
        int w = source.getWidth();
        int h = source.getHeight();
        Bitmap scaled = source;
        if (w > maxWidth) {
            int nh = Math.max(1, (int) ((long) h * maxWidth / w));
            scaled = Bitmap.createScaledBitmap(source, maxWidth, nh, true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out);
        if (scaled != source) scaled.recycle();
        return out.toByteArray();
    }

    private static JSONObject buildBody(String model, byte[] jpeg, List<String> known, boolean jsonMode,
                                        boolean thinkingOff, String priorAssistant,
                                        String systemPrompt, String glossary, boolean regionMode, byte[] regionJpeg) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.15);
        body.put("max_tokens", 16384);
        JSONArray messages = new JSONArray();

        JSONObject system = new JSONObject();
        system.put("role", "system");
        String systemText = systemPrompt == null ? "" : systemPrompt.trim();
        if (systemText.isEmpty()) systemText = Prompt.DEFAULT_SYSTEM;
        system.put("content", systemText);
        messages.put(system);

        JSONObject user = new JSONObject();
        user.put("role", "user");
        JSONArray content = new JSONArray();
        JSONObject text = new JSONObject();
        text.put("type", "text");
        text.put("text", Prompt.user(known, glossary, regionMode));
        content.put(text);
        JSONObject image = new JSONObject();
        image.put("type", "image_url");
        JSONObject imageUrl = new JSONObject();
        imageUrl.put("url", "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP));
        image.put("image_url", imageUrl);
        if (regionJpeg != null) {
            // 框选模式：先发放大后的选区（主图），再发整屏（上下文）
            JSONObject region = new JSONObject();
            region.put("type", "image_url");
            JSONObject regionUrl = new JSONObject();
            regionUrl.put("url", "data:image/jpeg;base64," + Base64.encodeToString(regionJpeg, Base64.NO_WRAP));
            region.put("image_url", regionUrl);
            content.put(region);
        }
        content.put(image);
        user.put("content", content);
        messages.put(user);
        if (priorAssistant != null && !priorAssistant.isEmpty()) {
            JSONObject prior = new JSONObject();
            prior.put("role", "assistant");
            prior.put("content", priorAssistant);
            messages.put(prior);
            JSONObject repair = new JSONObject();
            repair.put("role", "user");
            repair.put("content", Prompt.REPAIR);
            messages.put(repair);
        }
        body.put("messages", messages);
        if (jsonMode) {
            JSONObject format = new JSONObject();
            format.put("type", "json_object");
            body.put("response_format", format);
        }
        // DeepSeek 默认开思考模式：思维链会占用输出，翻译默认关掉；「精译」才打开
        JSONObject thinking = new JSONObject();
        thinking.put("type", thinkingOff ? "disabled" : "enabled");
        body.put("thinking", thinking);
        return body;
    }

    private static Reply send(String url, String apiKey, JSONObject body) throws Exception {
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            return new Reply(code, readAll(stream));
        } finally {
            conn.disconnect();
        }
    }

    /** 流式回调：每凑齐若干完整段落就通知一次。 */
    interface Progress {
        void onSegments(List<Translator.Segment> segments);
    }

    /** 统一入口：能流式就流式，先把出来的一段段显示出来。 */
    static Result translate(String baseUrl, String apiKey, String model, byte[] jpeg, List<String> known,
                            boolean thinkMode, String systemPrompt, String glossary, boolean stream, boolean regionMode,
                            byte[] regionJpeg, Progress progress) {
        if (!stream) {
            return translateOnce(baseUrl, apiKey, model, jpeg, known, thinkMode, systemPrompt, glossary, regionMode, regionJpeg);
        }
        Result result = new Result();
        HttpURLConnection conn = null;
        try {
            String url = (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + "chat/completions";
            JSONObject body = buildBody(model, jpeg, known, true, !thinkMode, null, systemPrompt, glossary, regionMode, regionJpeg);
            body.put("stream", true);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(180000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                result.error = "HTTP " + code + "：" + shorten(readAll(conn.getErrorStream()), 220);
                return result;
            }
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder acc = new StringBuilder();
            int reported = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                if ("[DONE]".equals(data)) break;
                try {
                    JSONObject chunk = new JSONObject(data);
                    JSONArray choices = chunk.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) continue;
                    JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                    if (delta == null) continue;
                    String piece = delta.optString("content", "");
                    if (piece.isEmpty()) continue;
                    acc.append(piece);
                    List<Segment> found = salvage(acc.toString());
                    if (found.size() > reported) {
                        if (progress != null) {
                            progress.onSegments(new ArrayList<>(found.subList(reported, found.size())));
                        }
                        reported = found.size();
                    }
                } catch (Throwable ignored) {
                    // 单条 SSE 解析失败不影响整体
                }
            }
            reader.close();
            result.raw = acc.toString();
            List<Segment> all = parseStrict(result.raw);
            if (all == null) {
                result.parsed = false;
                result.segments.addAll(salvage(result.raw));
            } else {
                result.parsed = true;
                result.segments.addAll(all);
            }
            return result;
        } catch (Throwable error) {
            result.error = error.getClass().getSimpleName() + "：" + String.valueOf(error.getMessage());
            return result;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static Result translateOnce(String baseUrl, String apiKey, String model, byte[] jpeg, List<String> known,
                                boolean thinkMode, String systemPrompt, String glossary, boolean regionMode,
                                byte[] regionJpeg) {
        Result result = new Result();
        try {
            String url = (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + "chat/completions";
            Reply reply = send(url, apiKey, buildBody(model, jpeg, known, true, !thinkMode, null, systemPrompt, glossary, regionMode, regionJpeg));
            // 网关不认某个字段就逐级降级：先去掉 thinking，再去掉 response_format
            if (!ok(reply) && mentions(reply, "thinking")) {
                reply = send(url, apiKey, buildBody(model, jpeg, known, true, false, null, systemPrompt, glossary, regionMode, regionJpeg));
            }
            if (!ok(reply) && (mentions(reply, "response_format") || mentions(reply, "json_object"))) {
                reply = send(url, apiKey, buildBody(model, jpeg, known, false, false, null, systemPrompt, glossary, regionMode, regionJpeg));
            }
            if (reply.code < 200 || reply.code >= 300) {
                result.error = "HTTP " + reply.code + "：" + shorten(reply.body, 220);
                return result;
            }
            JSONObject json = new JSONObject(reply.body);
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                result.error = "返回里没有 choices：" + shorten(reply.body, 180);
                return result;
            }
            JSONObject messageObject = choices.getJSONObject(0).optJSONObject("message");
            String message = "";
            if (messageObject != null) {
                Object contentValue = messageObject.opt("content");
                if (contentValue instanceof String) {
                    message = (String) contentValue;
                } else if (contentValue instanceof JSONArray) {
                    // 有些网关把 content 返回成分段数组
                    JSONArray parts = (JSONArray) contentValue;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < parts.length(); i++) {
                        Object part = parts.opt(i);
                        if (part instanceof JSONObject) sb.append(((JSONObject) part).optString("text", ""));
                        else if (part instanceof String) sb.append(part);
                    }
                    message = sb.toString();
                }
                if (message.trim().isEmpty()) {
                    String reasoning = messageObject.optString("reasoning_content", "");
                    if (!reasoning.trim().isEmpty()) message = reasoning;
                }
            }
            applyMessage(result, message);
            if (!result.parsed && !message.trim().isEmpty()) {
                // 模型写了分析而不是 JSON：带着它自己的话再要求一次，通常第二次就老实了
                try {
                    JSONObject repairBody = buildBody(model, jpeg, known, false, !thinkMode, message, systemPrompt, glossary, regionMode, regionJpeg);
                    Reply repairReply = send(url, apiKey, repairBody);
                    if (repairReply.code >= 200 && repairReply.code < 300) {
                        JSONObject repairJson = new JSONObject(repairReply.body);
                        JSONArray repairChoices = repairJson.optJSONArray("choices");
                        if (repairChoices != null && repairChoices.length() > 0) {
                            JSONObject repairMessage = repairChoices.getJSONObject(0).optJSONObject("message");
                            String repairText = repairMessage == null ? "" : repairMessage.optString("content", "");
                            if (!repairText.trim().isEmpty()) applyMessage(result, repairText);
                        }
                    }
                } catch (Throwable ignored) {
                    // 修复失败就保持原样，让上层显示原话
                }
            }
            return result;
        } catch (Throwable error) {
            result.error = error.getClass().getSimpleName() + "：" + String.valueOf(error.getMessage());
            return result;
        }
    }

    /** 把一条模型回复落成 segments；返回 JSON 是否合法。 */
    private static void applyMessage(Result result, String message) {
        result.raw = message;
        List<Segment> list = parseStrict(message);
        if (list == null) {
            result.parsed = false;
            result.segments.addAll(salvage(message));
        } else {
            result.parsed = true;
            result.segments.addAll(list);
        }
    }

    private static boolean ok(Reply reply) {
        return reply.code >= 200 && reply.code < 300;
    }

    private static boolean mentions(Reply reply, String needle) {
        return reply.body != null && reply.body.contains(needle);
    }

    /** 从被截断的回复里抢救完整的段落对。 */
    static List<Segment> salvage(String raw) {
        List<Segment> out = new ArrayList<>();
        if (raw == null) return out;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("\\{[^{}]*?\"en\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"[^{}]*?\"zh\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .matcher(raw);
        java.util.regex.Pattern orderPattern = java.util.regex.Pattern.compile("\"i\"\\s*:\\s*(\\d+)");
        while (matcher.find()) {
            try {
                JSONObject item = new JSONObject("{\"en\":\"" + matcher.group(1) + "\",\"zh\":\"" + matcher.group(2) + "\"}");
                String en = item.optString("en", "");
                String zh = item.optString("zh", "");
                if (en.trim().isEmpty() || zh.trim().isEmpty()) continue;
                java.util.regex.Matcher orderMatcher = orderPattern.matcher(matcher.group());
                int order = orderMatcher.find() ? Integer.parseInt(orderMatcher.group(1)) : 0;
                out.add(new Segment(en.trim(), zh.trim(), order));
            } catch (Throwable ignored) {
                // 单条坏掉就跳过
            }
        }
        return out;
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) > 0) out.write(buffer, 0, read);
        stream.close();
        return out.toString("UTF-8");
    }

    private static String shorten(String value, int max) {
        String s = value == null ? "" : value.replaceAll("[ \\t\\r\\n]+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 容忍 ```json 围栏、前后废话、退化成纯数组；JSON 不合法时返回 null（交给 salvage）。 */
    static List<Segment> parseStrict(String raw) {
        List<Segment> out = new ArrayList<>();
        String text = raw == null ? "" : raw.trim();
        int brace = text.indexOf('{');
        int bracket = text.indexOf('[');
        int start = brace < 0 ? bracket : (bracket < 0 ? brace : Math.min(brace, bracket));
        if (start > 0) text = text.substring(start);
        int endObj = text.lastIndexOf('}');
        int endArr = text.lastIndexOf(']');
        int end = Math.max(endObj, endArr);
        if (end >= 0) text = text.substring(0, end + 1);
        try {
            Object parsed = text.startsWith("[") ? new JSONArray(text) : new JSONObject(text);
            JSONArray list;
            if (parsed instanceof JSONArray) {
                list = (JSONArray) parsed;
            } else {
                list = ((JSONObject) parsed).optJSONArray("segments");
            }
            if (list == null) return out;
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                String en = item.optString("en", item.optString("src", ""));
                String zh = item.optString("zh", item.optString("zh_cn", ""));
                if (en.trim().isEmpty() || zh.trim().isEmpty()) continue;
                out.add(new Segment(en.trim(), zh.trim(), item.optInt("i", 0)));
            }
        } catch (Throwable ignored) {
            return null;
        }
        return out;
    }
}
