package com.rltranslator.overlay;

import android.content.Context;
import android.content.SharedPreferences;

/** 设置与去重记录的持久化。 */
final class Store {
    private static final String PREFS = "rltr";
    static final String KEY_API = "apiKey";
    static final String KEY_BASE = "baseUrl";
    static final String KEY_MODEL = "model";
    static final String KEY_INTERVAL = "intervalMs";
    static final String KEY_AUTO = "auto";
    static final String KEY_X = "posX";
    static final String KEY_Y = "posY";
    static final String KEY_SEEN = "seen";
    static final String KEY_OVERLAY = "overlayVisible";
    static final String KEY_THINK = "thinkMode";
    static final String KEY_PROMPT = "customPrompt";
    static final String KEY_GLOSSARY = "glossary";
    static final String KEY_STREAM = "streamMode";
    static final String KEY_CAT = "catTheme";

    static final String DEFAULT_BASE = "https://api.deepseek.com/v1";
    static final String DEFAULT_MODEL = "deepseek-flash";

    private Store() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String get(Context c, String key, String def) {
        return prefs(c).getString(key, def);
    }

    static void put(Context c, String key, String value) {
        prefs(c).edit().putString(key, value).apply();
    }

    static int getInt(Context c, String key, int def) {
        return prefs(c).getInt(key, def);
    }

    static void putInt(Context c, String key, int value) {
        prefs(c).edit().putInt(key, value).apply();
    }

    static boolean getBool(Context c, String key, boolean def) {
        return prefs(c).getBoolean(key, def);
    }

    static void putBool(Context c, String key, boolean value) {
        prefs(c).edit().putBoolean(key, value).apply();
    }
}
