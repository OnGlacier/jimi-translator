package com.rltranslator.overlay;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 无障碍服务：截屏 → 调模型 → 把新译文塞进悬浮窗。 */
public class CaptureService extends AccessibilityService implements Overlay.Host {

    private static volatile CaptureService instance;

    static CaptureService get() {
        return instance;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Dedupe dedupe = new Dedupe();

    private Overlay overlay;
    private boolean busy;
    private boolean auto;
    private RegionPicker picker;
    private android.graphics.Rect pendingCrop;
    private boolean pendingRegionMode;
    private boolean expandAfterCapture;
    private byte[] lastThumb;
    private int idleTicks;
    private int captures;
    private int segments;
    private int duplicates;
    private int failures;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        try {
            dedupe.load(this);
            overlay = new Overlay(this, this, dedupe);
            overlay.show();
            auto = Store.getBool(this, Store.KEY_AUTO, false);
            overlay.setAuto(auto);
            overlay.setStatus("待机");
            if (auto) scheduleAuto(600);
        } catch (Throwable error) {
            // 宁可少一个浮窗，也不能让服务崩溃——崩溃多了系统会直接把这个无障碍服务关掉
            android.util.Log.e("JimiTranslator", "onServiceConnected failed", error);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 只为拿到服务实例；具体读屏走 takeScreenshot。这里绝不抛异常。
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        try {
            instance = null;
            if (overlay != null) overlay.hide();
        } catch (Throwable ignored) {
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        try {
            instance = null;
            handler.removeCallbacksAndMessages(null);
            if (overlay != null) overlay.hide();
            io.shutdownNow();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 对外
    @Override
    public void onTranslateNow() {
        // 手动点「译」永远真翻，不做画面去重
        translateNow(true);
    }

    @Override
    public void onPickRegion() {
        if (picker == null) picker = new RegionPicker(this);
        if (overlay != null) overlay.collapse(true);
        expandAfterCapture = true;
        final int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        picker.show(this, rect -> {
            // 往外扩一圈：避免把一行字/一个单词从中间截断，模型就只肯翻半句
            android.graphics.Rect expanded = new android.graphics.Rect(
                rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad);
            pendingCrop = expanded;
            pendingRegionMode = true;
            translateNow(true);
        });
    }

    @Override
    public void onClear() {
        clearFeed();
    }

    @Override
    public void onDirectionChanged(String direction) {
        if (overlay == null) return;
        overlay.setStatus("方向：" + ("en2zh".equals(direction) ? "英译中"
            : ("zh2en".equals(direction) ? "中译英" : "自动")));
        if ("zh2en".equals(direction) && Dedupe.normalize("").isEmpty()) {
            // 方向切换后旧记录仍按原文去重，不需要清空
        }
    }

    @Override
    public void onToggleAuto() {
        auto = !auto;
        Store.putBool(this, Store.KEY_AUTO, auto);
        if (overlay != null) overlay.setAuto(auto);
        if (auto) {
            scheduleAuto(300);
        } else {
            handler.removeCallbacksAndMessages(null);
        }
    }

    /** 从配置页把浮窗叫回来。 */
    void showOverlay() {
        if (overlay == null) return;
        overlay.expand();
    }

    void clearFeed() {
        dedupe.clear();
        dedupe.save(this);
        lastThumb = null;
        idleTicks = 0;
        if (overlay != null) {
            overlay.clearFeed();
            overlay.setStatus("已清空，下一屏从头翻");
        }
        segments = 0;
        duplicates = 0;
        captures = 0;
        updateStats();
    }

    /** 32×24 灰度指纹：判断「画面到底变没变」，没变就不花模型的钱。 */
    private byte[] thumbOf(Bitmap bitmap) {
        Bitmap small = Bitmap.createScaledBitmap(bitmap, 32, 24, false);
        int[] pixels = new int[32 * 24];
        small.getPixels(pixels, 0, 32, 0, 0, 32, 24);
        small.recycle();
        byte[] out = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 0xff;
            int g = (p >> 8) & 0xff;
            int b = p & 0xff;
            out[i] = (byte) ((r * 299 + g * 587 + b * 114) / 1000);
        }
        return out;
    }

    private boolean screenChanged(byte[] thumb) {
        if (lastThumb == null || lastThumb.length != thumb.length) {
            lastThumb = thumb;
            return true;
        }
        int sum = 0;
        for (int i = 0; i < thumb.length; i++) {
            sum += Math.abs((thumb[i] & 0xff) - (lastThumb[i] & 0xff));
        }
        lastThumb = thumb;
        return (sum / (double) thumb.length) > 1.6;
    }

    void translateNow() {
        translateNow(false);
    }

    // ------------------------------------------------------------------ 截屏
    private void translateNow(boolean force) {
        if (busy) return;
        String key = Store.get(this, Store.KEY_API, "");
        if (key == null || key.trim().isEmpty()) {
            if (overlay != null) overlay.setStatus("先在 App 里填 API key");
            return;
        }
        busy = true;
        if (overlay != null) overlay.setStatus("截屏…");
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult result) {
                        Bitmap copy = null;
                        try {
                            HardwareBuffer buffer = result.getHardwareBuffer();
                            ColorSpace colorSpace = result.getColorSpace();
                            Bitmap wrapped = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
                            if (wrapped != null) copy = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                            buffer.close();
                        } catch (Throwable ignored) {
                        }
                        if (copy == null) {
                            busy = false;
                            failures++;
                            if (overlay != null) overlay.setStatus("截屏失败");
                            updateStats();
                            return;
                        }
                        if (expandAfterCapture) {
                            expandAfterCapture = false;
                            if (overlay != null) overlay.expand();
                        }
                        Bitmap region = null;
                        if (pendingCrop != null) {
                            android.graphics.Rect rect = pendingCrop;
                            pendingCrop = null;
                            region = cropRegion(copy, rect);
                            copy = drawRegionMark(copy, rect);
                        }
                        byte[] thumb = thumbOf(copy);
                        boolean changed = screenChanged(thumb);
                        if (!force && !changed) {
                            busy = false;
                            idleTicks++;
                            if (overlay != null) {
                                overlay.setStatus(idleTicks > 1 ? "画面没动，先不重复翻" : "本屏已翻完，等你继续滚");
                            }
                            updateStats();
                            return;
                        }
                        idleTicks = 0;
                        submit(copy, region);
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        busy = false;
                        failures++;
                        if (overlay != null) overlay.setStatus("截屏失败(" + errorText(errorCode) + ")");
                        updateStats();
                    }
                });
        } catch (Throwable error) {
            busy = false;
            failures++;
            if (overlay != null) overlay.setStatus("截屏异常：" + String.valueOf(error.getMessage()));
            updateStats();
        }
    }

    private static String errorText(int code) {
        switch (code) {
            case ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT:
                return "太频繁";
            case ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY:
                return "显示器无效";
            case ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS:
                return "无读屏权限";
            case ERROR_TAKE_SCREENSHOT_SECURE_WINDOW:
                return "安全窗口禁截";
            default:
                return "code " + code;
        }
    }

    /** 把选区裁出来（带内边距），作为主图发给模型。 */
    private Bitmap cropRegion(Bitmap source, android.graphics.Rect rect) {
        try {
            int left = Math.max(0, rect.left);
            int top = Math.max(0, rect.top);
            int width = Math.min(rect.width(), source.getWidth() - left);
            int height = Math.min(rect.height(), source.getHeight() - top);
            if (width < 32 || height < 32) return null;
            return Bitmap.createBitmap(source, left, top, width, height);
        } catch (Throwable error) {
            return null;
        }
    }

    /** 在整屏截图上画一个蓝色选区：模型既能看到上下文，又知道只翻框里。 */
    private Bitmap drawRegionMark(Bitmap source, android.graphics.Rect rect) {
        Bitmap target = source;
        try {
            if (!source.isMutable()) {
                Bitmap mutable = source.copy(Bitmap.Config.ARGB_8888, true);
                if (mutable != null) {
                    source.recycle();
                    target = mutable;
                }
            }
            android.graphics.Canvas canvas = new android.graphics.Canvas(target);
            android.graphics.Paint fill = new android.graphics.Paint();
            fill.setColor(0x1F3B82F6);
            canvas.drawRect(rect, fill);
            android.graphics.Paint stroke = new android.graphics.Paint();
            stroke.setStyle(android.graphics.Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(3f, 3f * getResources().getDisplayMetrics().density));
            stroke.setColor(0xFF1E6FE8);
            canvas.drawRect(rect, stroke);
        } catch (Throwable ignored) {
        }
        return target;
    }

    private void submit(Bitmap bitmap, Bitmap region) {
        String base = Store.get(this, Store.KEY_BASE, Store.DEFAULT_BASE);
        String key = Store.get(this, Store.KEY_API, "");
        String model = Store.get(this, Store.KEY_MODEL, Store.DEFAULT_MODEL);
        String systemPrompt = Store.get(this, Store.KEY_PROMPT, "");
        String direction = Store.get(this, Store.KEY_DIRECTION, "auto");
        String glossary = Store.get(this, Store.KEY_GLOSSARY, "");
        byte[] jpeg;
        byte[] regionJpeg = null;
        try {
            jpeg = Translator.toJpeg(bitmap, 1500, 80);
            if (region != null) {
                regionJpeg = Translator.toJpeg(region, 1400, 88);
                region.recycle();
            }
        } catch (Throwable error) {
            bitmap.recycle();
            busy = false;
            if (overlay != null) overlay.setStatus("图片处理失败");
            return;
        }
        bitmap.recycle();
        final List<String> known = dedupe.recent(26);
        if (overlay != null) overlay.setStatus("翻译中…");
        final boolean stream = Store.getBool(this, Store.KEY_STREAM, true);
        final byte[] regionForRequest = regionJpeg;
        final boolean regionMode = pendingRegionMode;
        pendingRegionMode = false;
        final boolean thinkMode = Store.getBool(this, Store.KEY_THINK, false);
        final java.util.Set<String> addedThisCall =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
        final int[] added = {0};
        final int[] skipped = {0};

        io.execute(() -> {
            Translator.Result result = Translator.translate(base, key, model, jpeg, known, thinkMode,
                systemPrompt, glossary, stream, regionMode, regionForRequest, direction, chunk -> handler.post(() -> {
                    if (chunk == null || chunk.isEmpty()) return;
                    int fresh = 0;
                    java.util.List<Translator.Segment> ordered = new java.util.ArrayList<>(chunk);
                    ordered.sort((a, b) -> Integer.compare(a.order == 0 ? 9999 : a.order, b.order == 0 ? 9999 : b.order));
                    for (Translator.Segment segment : ordered) {
                        String mark = Dedupe.normalize(segment.src);
                        if (addedThisCall.contains(mark)) continue;
                        if (dedupe.isDuplicate(segment.src)) { skipped[0]++; continue; }
                        dedupe.remember(segment.src);
                        addedThisCall.add(mark);
                        if (overlay != null) overlay.addEntry(segment.src, segment.dst);
                        added[0]++;
                        fresh++;
                    }
                    if (fresh > 0 && overlay != null) {
                        overlay.setStatus("流式输出中…已出 " + added[0] + " 段");
                    }
                }));
            handler.post(() -> {
                busy = false;
                captures++;
                if (result.error != null) {
                    failures++;
                    if (overlay != null) overlay.setStatus("失败：" + result.error);
                } else {
                    java.util.List<Translator.Segment> sorted = new java.util.ArrayList<>(result.segments);
                    sorted.sort((a, b) -> Integer.compare(a.order == 0 ? 9999 : a.order, b.order == 0 ? 9999 : b.order));
                    for (Translator.Segment segment : sorted) {
                        String mark = Dedupe.normalize(segment.src);
                        if (addedThisCall.contains(mark)) continue;
                        if (dedupe.isDuplicate(segment.src)) { skipped[0]++; continue; }
                        dedupe.remember(segment.src);
                        addedThisCall.add(mark);
                        if (overlay != null) overlay.addEntry(segment.src, segment.dst);
                        added[0]++;
                        if (added[0] >= 60) break;
                    }
                    dedupe.save(this);
                    segments += added[0];
                    duplicates += skipped[0];

                    if (overlay != null) {
                        if (added[0] > 0) {
                            overlay.setStatus("新增 " + added[0] + " 段");
                        } else if (!result.parsed && result.raw != null && !result.raw.trim().isEmpty()) {
                            String snippet = result.raw.replaceAll("[ \t\r\n]+", " ").trim();
                            if (snippet.length() > 110) snippet = snippet.substring(0, 110) + "…";
                            overlay.setStatus("模型没给 JSON，原话见下方");
                            overlay.addDebug("模型原话：" + snippet);
                        } else {
                            overlay.setStatus(result.parsed ? "本屏已翻完（等你继续滚）" : "本屏没有新正文");
                        }
                    }
                }
                updateStats();
            });
        });
    }

    private void updateStats() {
        if (overlay == null) return;
        overlay.setStats("新增 " + segments + " 段 · 跳过重复 " + duplicates
            + " · 截屏 " + captures + " 次" + (failures > 0 ? " · 失败 " + failures : ""));
    }

    // ------------------------------------------------------------------ 自动
    private void scheduleAuto(long delayMs) {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!auto) return;
                translateNow(false);
                int base = Math.max(3000, Store.getInt(CaptureService.this, Store.KEY_INTERVAL, 8000));
                long delay = idleTicks >= 3 ? Math.max(base, 20000) : (idleTicks >= 1 ? base * 2L : base);
                handler.postDelayed(this, delay);
            }
        }, delayMs);
    }
}
