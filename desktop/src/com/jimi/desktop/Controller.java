package com.jimi.desktop;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** 把截屏、模型调用、去重、浮窗串起来。 */
final class Controller implements Overlay.Host {

    private final Config config;
    private final Dedupe dedupe = new Dedupe();
    private Overlay overlay;
    private Timer timer;
    private boolean auto;
    private boolean busy;
    private byte[] lastThumb;
    private Rectangle pendingRegion;

    Controller(Config config) {
        this.config = config;
    }

    void start() {
        overlay = new Overlay(this);
        overlay.setDirection(config.direction);
        overlay.setVisible(true);
        overlay.setStatus("待机 · 点「框选」开始");
    }

    void showOverlay() {
        if (overlay == null) {
            start();
        } else {
            overlay.setVisible(true);
            overlay.expand();
        }
    }

    void toggleOverlay() {
        if (overlay == null) {
            start();
        } else {
            overlay.toggleVisible();
        }
    }

    // ---------------------------------------------------------------- Host

    @Override
    public void onPickRegion() {
        if (overlay != null) overlay.setVisible(false);
        SwingUtilities.invokeLater(() -> RegionPicker.pick(rect -> {
            pendingRegion = pad(rect);
            if (overlay != null) overlay.setVisible(true);
            translateNow(true);
        }));
    }

    @Override
    public void onToggleAuto() {
        auto = !auto;
        if (overlay != null) overlay.setAuto(auto);
        if (auto) {
            if (timer == null) {
                timer = new Timer(Math.max(3000, config.intervalMs), event -> translateNow(false));
                timer.setInitialDelay(300);
            }
            timer.start();
            if (overlay != null) overlay.setStatus("自动翻译中 · 每 " + Math.max(3, config.intervalMs / 1000) + " 秒");
        } else {
            if (timer != null) timer.stop();
            if (overlay != null) overlay.setStatus("已停止");
        }
    }

    @Override
    public void onClear() {
        dedupe.clear();
        lastThumb = null;
        if (overlay != null) overlay.clearFeed();
    }

    @Override
    public void onDirectionChanged(String direction) {
        config.direction = direction;
        config.save();
        if (overlay != null) {
            overlay.setStatus("方向：" + Overlay.labelFor(direction));
        }
    }

    @Override
    public boolean isAuto() {
        return auto;
    }

    // ---------------------------------------------------------------- 翻译

    private void translateNow(boolean force) {
        if (busy) return;
        if (!force && auto == false) return;
        busy = true;
        Rectangle region = pendingRegion;
        pendingRegion = null;
        Thread worker = new Thread(() -> {
            try {
                doTranslate(region, force);
            } catch (Throwable error) {
                String message = String.valueOf(error.getMessage());
                if (overlay != null) overlay.setStatus("失败：" + (message.length() > 60 ? message.substring(0, 60) : message));
            } finally {
                busy = false;
            }
        }, "jimi-translate");
        worker.setDaemon(true);
        worker.start();
    }

    private void doTranslate(Rectangle region, boolean force) throws Exception {
        BufferedImage screen = Screen.grab();
        byte[] thumb = Screen.thumb(screen);
        if (!force && !Screen.changed(lastThumb, thumb)) {
            if (overlay != null) overlay.setStatus("画面没变，跳过");
            return;
        }
        lastThumb = thumb;

        byte[] fullJpeg = Screen.jpeg(screen, 1500, 0.8f);
        byte[] regionJpeg = null;
        if (region != null && region.width > 8 && region.height > 8) {
            regionJpeg = Screen.jpeg(Screen.crop(screen, region), 1400, 0.88f);
        }

        List<String> known = dedupe.recent(26);
        List<TranslateClient.Segment> segments = TranslateClient.translate(
            config, fullJpeg, regionJpeg, known, config.direction, new TranslateClient.Listener() {
                @Override
                public void onSegment(TranslateClient.Segment segment) {
                    show(segment);
                }

                @Override
                public void onStatus(String text) {
                    if (overlay != null) overlay.setStatus(text);
                }
            });

        int added = 0;
        for (TranslateClient.Segment segment : segments) {
            if (show(segment)) added++;
        }
        if (overlay != null) {
            overlay.setStatus(added == 0 ? "本屏没有新内容" : ("新增 " + added + " 段"));
        }
    }

    private final List<String> shown = new ArrayList<>();

    private boolean show(TranslateClient.Segment segment) {
        if (segment == null || segment.src == null || segment.dst == null) return false;
        if (dedupe.isDuplicate(segment.src)) return false;
        dedupe.remember(segment.src);
        if (overlay != null) overlay.addEntry(segment.src, segment.dst);
        shown.add(segment.src);
        return true;
    }

    private static Rectangle pad(Rectangle rect) {
        int pad = 12;
        Rectangle screen = Screen.bounds();
        return new Rectangle(
            Math.max(0, rect.x - pad),
            Math.max(0, rect.y - pad),
            Math.min(screen.width - Math.max(0, rect.x - pad), rect.width + pad * 2),
            Math.min(screen.height - Math.max(0, rect.y - pad), rect.height + pad * 2));
    }

    Overlay overlay() {
        return overlay;
    }

    Config config() {
        return config;
    }
}
