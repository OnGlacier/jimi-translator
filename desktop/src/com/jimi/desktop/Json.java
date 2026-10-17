package com.jimi.desktop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON：够用就行——解析模型返回的 {@code {"segments":[...]}}，以及转义我们发出去的字符串。
 * 不引入任何第三方库，保持「一个 jar 就能跑」。
 */
final class Json {

    private Json() {}

    // ---------------------------------------------------------------- 解析

    static Object parse(String text) {
        if (text == null) return null;
        Parser parser = new Parser(text);
        parser.skipSpace();
        Object value = parser.value();
        parser.skipSpace();
        return value;
    }

    private static final class Parser {
        private final String source;
        private int index;

        Parser(String source) {
            this.source = source;
        }

        void skipSpace() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
        }

        Object value() {
            skipSpace();
            if (index >= source.length()) return null;
            char c = source.charAt(index);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return number();
            }
        }

        private void expect(String word) {
            if (!source.startsWith(word, index)) throw new IllegalArgumentException("JSON 非法位置 " + index);
            index += word.length();
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            index++; // {
            skipSpace();
            if (index < source.length() && source.charAt(index) == '}') {
                index++;
                return map;
            }
            while (index < source.length()) {
                skipSpace();
                String key = string();
                skipSpace();
                if (index >= source.length() || source.charAt(index) != ':') {
                    throw new IllegalArgumentException("缺少冒号 @" + index);
                }
                index++;
                Object value = value();
                map.put(key, value);
                skipSpace();
                if (index >= source.length()) break;
                char c = source.charAt(index);
                if (c == ',') {
                    index++;
                    continue;
                }
                if (c == '}') {
                    index++;
                    break;
                }
                throw new IllegalArgumentException("对象里出现意外字符 " + c);
            }
            return map;
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            index++; // [
            skipSpace();
            if (index < source.length() && source.charAt(index) == ']') {
                index++;
                return list;
            }
            while (index < source.length()) {
                list.add(value());
                skipSpace();
                if (index >= source.length()) break;
                char c = source.charAt(index);
                if (c == ',') {
                    index++;
                    continue;
                }
                if (c == ']') {
                    index++;
                    break;
                }
                throw new IllegalArgumentException("数组里出现意外字符 " + c);
            }
            return list;
        }

        private String string() {
            skipSpace();
            if (index >= source.length() || source.charAt(index) != '"') {
                throw new IllegalArgumentException("期望字符串 @" + index);
            }
            index++;
            StringBuilder sb = new StringBuilder();
            while (index < source.length()) {
                char c = source.charAt(index++);
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (index >= source.length()) break;
                char esc = source.charAt(index++);
                switch (esc) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case '/': sb.append('/'); break;
                    case '\\': sb.append('\\'); break;
                    case '"': sb.append('"'); break;
                    case 'u':
                        if (index + 4 <= source.length()) {
                            sb.append((char) Integer.parseInt(source.substring(index, index + 4), 16));
                            index += 4;
                        }
                        break;
                    default:
                        sb.append(esc);
                }
            }
            return sb.toString();
        }

        private Object number() {
            int start = index;
            while (index < source.length() && "+-0123456789.eE".indexOf(source.charAt(index)) >= 0) index++;
            String raw = source.substring(start, index);
            if (raw.isEmpty()) return null;
            try {
                return Double.parseDouble(raw);
            } catch (NumberFormatException error) {
                return null;
            }
        }
    }

    // ---------------------------------------------------------------- 生成

    static String escape(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 取值

    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asArray(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }
}
