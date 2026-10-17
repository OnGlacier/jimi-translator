package com.jimi.desktop;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/** 悬浮译文卡：置顶、可拖动、可缩放、可全屏。窗口不透明，避免无合成器的 Linux 上闪烁。 */
final class Overlay {

    interface Host {
        void onPickRegion();

        void onToggleAuto();

        void onClear();

        void onDirectionChanged(String direction);

        boolean isAuto();
    }

    private static final int DEFAULT_WIDTH = 470;
    private static final int DEFAULT_HEIGHT = 430;
    private static final Color INK = new Color(0xE6, 0xED, 0xF3);
    private static final Color INK_SOFT = new Color(0x8B, 0x98, 0xA9);
    private static final Color CARD_DARK = new Color(0x0F, 0x13, 0x19);
    private static final Color CARD_LIGHT = new Color(0x2A, 0x33, 0x3F);
    private static final Color CHIP_BG = new Color(0x2A, 0x33, 0x3F);
    private static final Color ACCENT = new Color(0xF5, 0x9E, 0x0B);

    private final Host host;
    private JFrame frame;
    private JPanel root;
    private JPanel feed;
    private JScrollPane scroll;
    private JLabel statusLabel;
    private JLabel autoChip;
    private JLabel directionChip;
    private JPanel moreRow;

    private boolean bilingual = true;
    private boolean collapsed;
    private int alphaPct = 86;
    private String direction = "auto";
    private boolean maximized;
    private Rectangle restoreBounds;

    private final List<JTextArea> sourceViews = new ArrayList<>();
    private final List<JTextArea> bodyViews = new ArrayList<>();

    Overlay(Host host) {
        this.host = host;
        build();
    }

    // ---------------------------------------------------------------- 构建

    private void build() {
        frame = new JFrame("基米翻译");
        frame.setUndecorated(true);
        frame.setAlwaysOnTop(true);
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        frame.setLocationRelativeTo(null);
        frame.setBackground(CARD_DARK);

        root = new JPanel(new BorderLayout());
        root.setBackground(CARD_DARK);
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));

        // ---- 顶部：标题 + 状态 + 全屏 ----
        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setBackground(CARD_DARK);

        JPanel left = new JPanel(new BorderLayout(6, 0));
        left.setBackground(CARD_DARK);
        JLabel title = new JLabel("🐱 基米翻译");
        title.setForeground(INK);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        title.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        statusLabel = new JLabel("待机");
        statusLabel.setForeground(INK_SOFT);
        statusLabel.setFont(statusLabel.getFont().deriveFont(11.5f));
        left.add(title, BorderLayout.WEST);
        left.add(statusLabel, BorderLayout.CENTER);
        header.add(left, BorderLayout.CENTER);

        JLabel fullChip = chip("⛶", false);
        fullChip.setToolTipText("全屏 / 还原（Esc 也能退出全屏）");
        fullChip.addMouseListener(click(this::toggleMaximize));
        header.add(fullChip, BorderLayout.EAST);

        // 拖动：标题、状态、空白处都能拖
        DragHandler drag = new DragHandler();
        for (JComponent area : new JComponent[] {header, left, title, statusLabel}) {
            area.addMouseListener(drag);
            area.addMouseMotionListener(drag);
        }

        // ---- 动作行 ----
        JPanel actions = new JPanel();
        actions.setBackground(CARD_DARK);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));

        JLabel regionChip = chip("框选", true);
        regionChip.addMouseListener(click(host::onPickRegion));
        autoChip = chip("自动", false);
        autoChip.addMouseListener(click(host::onToggleAuto));
        directionChip = chip(labelFor(direction), false);
        directionChip.addMouseListener(click(this::cycleDirection));
        JLabel alphaChip = chip("不透明度", false);
        alphaChip.addMouseListener(click(() -> {
            moreRow.setVisible(!moreRow.isVisible());
            root.revalidate();
        }));
        JLabel clearChip = chip("清空", false);
        clearChip.addMouseListener(click(host::onClear));
        JLabel closeChip = chip("收起", false);
        closeChip.addMouseListener(click(this::collapse));

        actions.add(regionChip);
        actions.add(Box.createHorizontalStrut(5));
        actions.add(autoChip);
        actions.add(Box.createHorizontalStrut(5));
        actions.add(directionChip);
        actions.add(Box.createHorizontalStrut(5));
        actions.add(alphaChip);
        actions.add(Box.createHorizontalStrut(5));
        actions.add(clearChip);
        actions.add(Box.createHorizontalStrut(5));
        actions.add(closeChip);
        actions.add(Box.createHorizontalGlue());

        // ---- 折叠行 ----
        moreRow = new JPanel();
        moreRow.setBackground(CARD_DARK);
        moreRow.setLayout(new BoxLayout(moreRow, BoxLayout.Y_AXIS));
        moreRow.setVisible(false);
        moreRow.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        JSlider alphaSlider = new JSlider(30, 100, alphaPct);
        alphaSlider.setOpaque(false);
        alphaSlider.setForeground(INK_SOFT);
        JLabel alphaLabel = new JLabel("背景 " + alphaPct + "%");
        alphaLabel.setForeground(INK_SOFT);
        alphaLabel.setFont(alphaLabel.getFont().deriveFont(11f));
        alphaSlider.addChangeListener(event -> {
            alphaPct = alphaSlider.getValue();
            alphaLabel.setText("背景 " + alphaPct + "%");
            applyCardColor();
        });

        JPanel alphaRow = new JPanel(new BorderLayout(8, 0));
        alphaRow.setBackground(CARD_DARK);
        alphaRow.add(alphaLabel, BorderLayout.WEST);
        alphaRow.add(alphaSlider, BorderLayout.CENTER);

        JLabel bilingualChip = chip(bilingual ? "显示原文 · 开" : "显示原文 · 关", bilingual);
        bilingualChip.addMouseListener(click(() -> {
            bilingual = !bilingual;
            bilingualChip.setText(bilingual ? "显示原文 · 开" : "显示原文 · 关");
            paintChip(bilingualChip, bilingual);
            applyBilingual();
        }));
        JPanel displayRow = new JPanel(new BorderLayout());
        displayRow.setBackground(CARD_DARK);
        displayRow.add(bilingualChip, BorderLayout.WEST);

        moreRow.add(alphaRow);
        moreRow.add(Box.createVerticalStrut(6));
        moreRow.add(displayRow);

        JPanel top = new JPanel();
        top.setBackground(CARD_DARK);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(header);
        top.add(Box.createVerticalStrut(8));
        top.add(actions);
        top.add(moreRow);

        // ---- 译文流 ----
        feed = new JPanel();
        feed.setBackground(CARD_DARK);
        feed.setLayout(new BoxLayout(feed, BoxLayout.Y_AXIS));
        JPanel feedHolder = new JPanel(new BorderLayout());
        feedHolder.setBackground(CARD_DARK);
        feedHolder.add(feed, BorderLayout.NORTH);

        scroll = new JScrollPane(feedHolder);
        scroll.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        scroll.setBackground(CARD_DARK);
        scroll.getViewport().setBackground(CARD_DARK);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(24);

        // ---- 右下角缩放 ----
        JLabel grip = new JLabel("◢");
        grip.setForeground(new Color(255, 255, 255, 90));
        grip.setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
        grip.setToolTipText("拖动缩放窗口");
        MouseAdapter resizeHandler = new MouseAdapter() {
            private Point start;
            private Dimension size;

            @Override
            public void mousePressed(MouseEvent event) {
                start = event.getLocationOnScreen();
                size = frame.getSize();
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (start == null || size == null) return;
                maximized = false;
                Point now = event.getLocationOnScreen();
                int width = Math.max(360, size.width + now.x - start.x);
                int height = Math.max(240, size.height + now.y - start.y);
                frame.setSize(width, height);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                start = null;
            }
        };
        grip.addMouseListener(resizeHandler);
        grip.addMouseMotionListener(resizeHandler);
        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(CARD_DARK);
        south.add(grip, BorderLayout.EAST);

        root.add(top, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        frame.setContentPane(root);
        bindEscape();
        applyCardColor();
    }

    private void bindEscape() {
        JComponent pane = frame.getRootPane();
        pane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "exit-fullscreen");
        pane.getActionMap().put("exit-fullscreen", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (maximized) toggleMaximize();
            }
        });
    }

    // ---------------------------------------------------------------- 样式

    private interface Action {
        void run();
    }

    private static MouseAdapter click(Action action) {
        return new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                action.run();
            }
        };
    }

    private JLabel chip(String text, boolean accented) {
        JLabel label = new JLabel(text);
        label.setOpaque(true);
        label.setBorder(BorderFactory.createEmptyBorder(5, 9, 5, 9));
        label.setForeground(accented ? Color.WHITE : INK);
        label.setBackground(accented ? ACCENT : CHIP_BG);
        label.setFont(label.getFont().deriveFont(12f));
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return label;
    }

    private void paintChip(JLabel label, boolean accented) {
        label.setBackground(accented ? ACCENT : CHIP_BG);
        label.setForeground(accented ? Color.WHITE : INK);
    }

    /** 背景深浅：不透明度越低越接近浅灰，越高越接近卡片深色。 */
    private void applyCardColor() {
        float ratio = Math.max(0.3f, Math.min(1f, alphaPct / 100f));
        int r = (int) (CARD_LIGHT.getRed() + (CARD_DARK.getRed() - CARD_LIGHT.getRed()) * ratio);
        int g = (int) (CARD_LIGHT.getGreen() + (CARD_DARK.getGreen() - CARD_LIGHT.getGreen()) * ratio);
        int b = (int) (CARD_LIGHT.getBlue() + (CARD_DARK.getBlue() - CARD_LIGHT.getBlue()) * ratio);
        Color color = new Color(r, g, b);
        frame.setBackground(color);
        root.setBackground(color);
        for (java.awt.Component child : allChildren(root)) {
            if (child instanceof JPanel) {
                child.setBackground(color);
            }
        }
        root.repaint();
    }

    private static List<java.awt.Component> allChildren(java.awt.Container parent) {
        List<java.awt.Component> result = new ArrayList<>();
        for (java.awt.Component child : parent.getComponents()) {
            result.add(child);
            if (child instanceof java.awt.Container) {
                result.addAll(allChildren((java.awt.Container) child));
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- 交互

    private void cycleDirection() {
        direction = "auto".equals(direction) ? "en2zh" : ("en2zh".equals(direction) ? "zh2en" : "auto");
        directionChip.setText(labelFor(direction));
        paintChip(directionChip, !"auto".equals(direction));
        host.onDirectionChanged(direction);
    }

    static String labelFor(String value) {
        if ("en2zh".equals(value)) return "英译中";
        if ("zh2en".equals(value)) return "中译英";
        return "双向";
    }

    void setDirection(String value) {
        direction = value;
        if (directionChip != null) {
            directionChip.setText(labelFor(value));
            paintChip(directionChip, !"auto".equals(value));
        }
    }

    void setStatus(String text) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(text));
    }

    void setAuto(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            autoChip.setText(enabled ? "自动·开" : "自动");
            paintChip(autoChip, enabled);
        });
    }

    void setVisible(boolean visible) {
        SwingUtilities.invokeLater(() -> {
            frame.setVisible(visible);
            if (visible) {
                frame.toFront();
                frame.requestFocus();
            }
        });
    }

    void toggleVisible() {
        SwingUtilities.invokeLater(() -> {
            frame.setVisible(!frame.isVisible());
            if (frame.isVisible()) {
                frame.toFront();
                frame.requestFocus();
            }
        });
    }

    private void collapse() {
        collapsed = true;
        SwingUtilities.invokeLater(() -> {
            frame.setSize(72, 72);
            frame.setContentPane(bubble());
            frame.revalidate();
            frame.repaint();
        });
    }

    void expand() {
        collapsed = false;
        SwingUtilities.invokeLater(() -> {
            frame.setContentPane(root);
            if (maximized) {
                frame.setBounds(Screen.bounds());
            } else {
                frame.setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
                frame.setLocationRelativeTo(null);
            }
            root.revalidate();
            root.repaint();
            frame.toFront();
        });
    }

    boolean isCollapsed() {
        return collapsed;
    }

    private JPanel bubble() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ACCENT);
        JLabel cat = new JLabel("🐱", JLabel.CENTER);
        cat.setFont(cat.getFont().deriveFont(28f));
        panel.add(cat, BorderLayout.CENTER);
        panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter drag = new MouseAdapter() {
            private Point start;
            private Point frameStart;
            private boolean moved;

            @Override
            public void mousePressed(MouseEvent event) {
                start = event.getLocationOnScreen();
                frameStart = frame.getLocation();
                moved = false;
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (start == null) return;
                Point now = event.getLocationOnScreen();
                if (Math.abs(now.x - start.x) > 3 || Math.abs(now.y - start.y) > 3) moved = true;
                frame.setLocation(frameStart.x + now.x - start.x, frameStart.y + now.y - start.y);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (!moved) expand();
                start = null;
            }
        };
        panel.addMouseListener(drag);
        panel.addMouseMotionListener(drag);
        return panel;
    }

    void toggleMaximize() {
        Rectangle screen = Screen.bounds();
        if (maximized) {
            frame.setBounds(restoreBounds != null ? restoreBounds
                : new Rectangle(screen.width / 2 - DEFAULT_WIDTH / 2, screen.height / 2 - DEFAULT_HEIGHT / 2,
                    DEFAULT_WIDTH, DEFAULT_HEIGHT));
            maximized = false;
        } else {
            restoreBounds = frame.getBounds();
            frame.setBounds(screen);
            maximized = true;
        }
        frame.validate();
        frame.repaint();
    }

    private final class DragHandler extends MouseAdapter {
        private Point start;
        private Point frameStart;

        @Override
        public void mousePressed(MouseEvent event) {
            start = event.getLocationOnScreen();
            frameStart = frame.getLocation();
        }

        @Override
        public void mouseDragged(MouseEvent event) {
            if (start == null || frameStart == null) return;
            Point now = event.getLocationOnScreen();
            frame.setLocation(frameStart.x + now.x - start.x, frameStart.y + now.y - start.y);
        }

        @Override
        public void mouseReleased(MouseEvent event) {
            start = null;
        }
    }

    // ---------------------------------------------------------------- 内容

    void addEntry(String source, String translation) {
        SwingUtilities.invokeLater(() -> {
            if (source != null && !source.trim().isEmpty()) {
                JTextArea origin = textArea(source.trim(), true);
                origin.setVisible(bilingual);
                feed.add(origin);
                sourceViews.add(origin);
            }
            JTextArea body = textArea(translation, false);
            feed.add(body);
            feed.add(Box.createVerticalStrut(10));
            bodyViews.add(body);
            feed.revalidate();
            SwingUtilities.invokeLater(() -> {
                JScrollBar bar = scroll.getVerticalScrollBar();
                bar.setValue(bar.getMaximum());
            });
        });
    }

    private JTextArea textArea(String text, boolean secondary) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFocusable(false);
        area.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        if (secondary) {
            area.setForeground(INK_SOFT);
            area.setFont(area.getFont().deriveFont(11f));
        } else {
            area.setForeground(INK);
            area.setFont(area.getFont().deriveFont(14.5f));
        }
        return area;
    }

    private void applyBilingual() {
        for (JTextArea view : sourceViews) view.setVisible(bilingual);
        feed.revalidate();
        feed.repaint();
    }

    void clearFeed() {
        SwingUtilities.invokeLater(() -> {
            feed.removeAll();
            sourceViews.clear();
            bodyViews.clear();
            feed.revalidate();
            feed.repaint();
            setStatus("已清空");
        });
    }

    JFrame frame() {
        return frame;
    }
}
