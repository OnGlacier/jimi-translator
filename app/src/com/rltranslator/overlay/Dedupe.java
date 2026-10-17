package com.rltranslator.overlay;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 句子级去重：归一化 key 精确匹配 + 词集合 Jaccard，挡住 PDF 滚动造成的半句重复。 */
final class Dedupe {

    static final class Entry {
        final String key;
        final String[] words;
        final String src;

        Entry(String key, String[] words, String src) {
            this.key = key;
            this.words = words;
            this.src = src;
        }
    }

    private final List<Entry> seen = new ArrayList<>();

    static String normalize(String text) {
        if (text == null) return "";
        String s = text.toLowerCase();
        s = s.replace('\u00ad', ' ');
        s = s.replaceAll("[-–—]+", " ");
        s = s.replaceAll("[^\\p{L}\\p{N}\\s]+", " ");
        return s.replaceAll("\\s+", " ").trim();
    }

    static String[] words(String text) {
        String[] parts = normalize(text).split(" ");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (part.length() >= 3) out.add(part);
        }
        return out.toArray(new String[0]);
    }

    private static double jaccard(String[] a, String[] b) {
        if (a.length == 0 || b.length == 0) return 0;
        Set<String> sa = new HashSet<>(Arrays.asList(a));
        Set<String> sb = new HashSet<>(Arrays.asList(b));
        int inter = 0;
        for (String w : sb) if (sa.contains(w)) inter++;
        int union = sa.size() + sb.size() - inter;
        return union == 0 ? 0 : (double) inter / union;
    }

    boolean isDuplicate(String en) {
        String key = normalize(en);
        if (key.length() < 8) return true;
        String[] w = words(en);
        for (Entry e : seen) {
            if (e.key.equals(key)) return true;
            if (w.length >= 6 && e.words.length >= 6 && jaccard(e.words, w) >= 0.72) return true;
        }
        return false;
    }

    void remember(String en) {
        seen.add(new Entry(normalize(en), words(en), shorten(en, 240)));
        while (seen.size() > 300) seen.remove(0);
    }

    /** 最近若干条原文，给模型当「已翻译」名单。 */
    List<String> recent(int count) {
        List<String> out = new ArrayList<>();
        int from = Math.max(0, seen.size() - count);
        for (int i = from; i < seen.size(); i++) {
            String s = seen.get(i).src;
            if (s != null && !s.isEmpty()) out.add(s);
        }
        return out;
    }

    void clear() {
        seen.clear();
    }

    private static String shorten(String value, int max) {
        String s = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max);
    }

    void save(Context c) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : seen) {
            sb.append(e.key).append('\t');
            for (int i = 0; i < e.words.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(e.words[i]);
            }
            sb.append('\t').append(e.src == null ? "" : e.src.replace('\t', ' ')).append('\n');
        }
        Store.put(c, Store.KEY_SEEN, sb.toString());
    }

    void load(Context c) {
        String raw = Store.get(c, Store.KEY_SEEN, "");
        if (raw == null || raw.isEmpty()) return;
        seen.clear();
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t", -1);
            if (parts.length < 2) continue;
            String[] words = parts[1].isEmpty() ? new String[0] : parts[1].split(",");
            String src = parts.length > 2 ? parts[2] : "";
            seen.add(new Entry(parts[0], words, src));
        }
        while (seen.size() > 300) seen.remove(0);
    }
}
