package com.jimi.desktop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 句子级去重：归一化 key 完全相等，或词集合 Jaccard ≥ 0.72，都算重复。 */
final class Dedupe {

    private static final int MAX = 300;
    private static final double THRESHOLD = 0.72;
    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
        "the", "and", "for", "with", "that", "this", "from", "are", "was", "were", "have", "has",
        "had", "not", "but", "you", "can", "will", "our", "their", "its", "into", "than", "then",
        "there", "these", "those", "such", "when", "which", "while", "also", "been", "being"));

    private final Map<String, Set<String>> seen = new LinkedHashMap<>();

    static String normalize(String text) {
        if (text == null) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        lower = lower.replace('-', ' ').replace('—', ' ').replace('–', ' ');
        lower = lower.replaceAll("[^\\p{L}\\p{N}\\s]", " ");
        return lower.replaceAll("\\s+", " ").trim();
    }

    private static Set<String> words(String text) {
        Set<String> result = new HashSet<>();
        for (String word : normalize(text).split(" ")) {
            if (word.length() >= 3 && !STOP.contains(word)) result.add(word);
        }
        return result;
    }

    boolean isDuplicate(String text) {
        String key = normalize(text);
        if (key.isEmpty()) return false;
        if (seen.containsKey(key)) return true;
        Set<String> words = words(text);
        if (words.isEmpty()) return false;
        for (Set<String> other : seen.values()) {
            if (other.isEmpty()) continue;
            int inter = 0;
            for (String word : words) {
                if (other.contains(word)) inter++;
            }
            double union = words.size() + other.size() - inter;
            if (union > 0 && inter / union >= THRESHOLD) return true;
        }
        return false;
    }

    void remember(String text) {
        String key = normalize(text);
        if (key.isEmpty()) return;
        seen.put(key, words(text));
        while (seen.size() > MAX) {
            String first = seen.keySet().iterator().next();
            seen.remove(first);
        }
    }

    void clear() {
        seen.clear();
    }

    /** 给模型看的「已翻译」列表，最近的排在前面。 */
    List<String> recent(int limit) {
        List<String> keys = new ArrayList<>(seen.keySet());
        List<String> result = new ArrayList<>();
        for (int i = keys.size() - 1; i >= 0 && result.size() < limit; i--) {
            result.add(keys.get(i));
        }
        return result;
    }
}
