package com.jimi.desktop;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.MouseMotionAdapter;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** 悬浮译文卡：始终置顶、可拖动、可收起成猫猫圆钮。 */
final class Overlay {

    interface Host {
        void onPickRegion();

        void onToggleAuto();

        void onClear();

        void onDirectionChanged(String direction);

        boolean isAuto();
    }

    private static final Color INK = new Color(0xE6, 0xED, 0xF3);
    private static final Color INK_SOFT = new Color(0x8B, 0x98, 0xA9);
    private static final Color CARD_BG = new Color(15, 19, 25);
    private static final Color CHIP_BG = new Color(42, 51, 63);
    private static final Color ACCENT = new Color(0xF5, 0x9E, 0x0B);

    private static final int DEFAULT_WIDTH = 470;
    private static final int DEFAULT_HEIGHT = 430;
    private java.awt.Rectangle restoreBounds;
    private boolean maximized;

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
    private final List<JTextArea> sourceViews = new ArrayList<>();
    private final List<JTextArea> bodyViews = new ArrayList<>();

    Overlay(Host host) {
        this.host = host;
        build();
    }

    private static boolean translucentSupported() {
        try {
            return !GraphicsEnvironment.isHeadless()
                && GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().isWindowTranslucencySupported(
                    java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        } catch (Throwable error) {
            return false;
        }
    }

    private void build() {
        frame = new JFrame("基米翻译");
        frame.setUndecorated(true);
        frame.setAlwaysOnTop(true);
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        frame.setLocationRelativeTo(null);
        if (translucentSupported()) {
            frame.setBackground(new Color(0, 0, 0, 0));
        }

        root = new JPanel(null) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int alpha = Math.max(30, Math.min(100, alphaPct)) * 255 / 100;
                Color bg = new Color(CARD_BG.getRed(), CARD_BG.getGreen(), CARD_BG.getBlue(), alpha);
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
                g2.setColor(new Color(255, 255, 255, 26));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
                g2.dispose();
            }
        };
        root.setOpaque(false);
        root.setLayout(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        // ---- 顶部 ----
        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setOpaque(false);
        JLabel title = new JLabel("🐱 基米翻译");
        title.setForeground(INK);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        JPanel left = new JPanel(new BorderLayout(6, 0));
        left.setOpaque(false);
        left.add(title, BorderLayout.WEST);
        statusLabel = new JLabel("待机");
        statusLabel.setForeground(INK_SOFT);
        statusLabel.setFont(statusLabel.getFont().deriveFont(11.5f));
        left.add(statusLabel, BorderLayout.CENTER);
        header.add(left, BorderLayout.CENTER);

        JLabel fullChip = chip("⛶", false);
        fullChip.setToolTipText("放大到全屏 / 还原（双击标题栏也行）");
        fullChip.addMouseListener(click(this::toggleMaximize));
        header.add(fullChip, BorderLayout.EAST);
        header.addMouseListener(new DragHandler());
        header.addMouseMotionListener(new DragHandler());
        title.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) toggleMaximize();
            }
        });

        // ---- 按钮行 ----
        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

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
        actions.add(Box.createHorizontalStrut(6));
        actions.add(autoChip);
        actions.add(Box.createHorizontalStrut(6));
        actions.add(directionChip);
        actions.add(Box.createHorizontalStrut(6));
        actions.add(alphaChip);
        actions.add(Box.createHorizontalStrut(6));
        actions.add(clearChip);
        actions.add(Box.createHorizontalStrut(6));
        actions.add(closeChip);
        actions.add(Box.createHorizontalGlue());

        // ---- 折叠行：透明度 + 显示原文 ----
        moreRow = new JPanel();
        moreRow.setOpaque(false);
        moreRow.setLayout(new BoxLayout(moreRow, BoxLayout.Y_AXIS));
        moreRow.setVisible(false);
        moreRow.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        JSlider slider = new JSlider(30, 100, alphaPct);
        slider.setOpaque(false);
        slider.setForeground(INK_SOFT);
        slider.addChangeListener(event -> {
            alphaPct = slider.getValue();
            root.repaint();
        });
        JLabel alphaLabel = new JLabel("背景 " + alphaPct + "%");
        alphaLabel.setForeground(INK_SOFT);
        alphaLabel.setFont(alphaLabel.getFont().deriveFont(11f));
        slider.addChangeListener(event -> alphaLabel.setText("背景 " + slider.getValue() + "%"));

        JPanel alphaRow = new JPanel(new BorderLayout(8, 0));
        alphaRow.setOpaque(false);
        alphaRow.add(alphaLabel, BorderLayout.WEST);
        alphaRow.add(slider, BorderLayout.CENTER);

        JLabel bilingualChip = chip(bilingual ? "显示原文 · 开" : "显示原文 · 关", bilingual);
        bilingualChip.addMouseListener(click(() -> {
            bilingual = !bilingual;
            bilingualChip.setText(bilingual ? "显示原文 · 开" : "显示原文 · 关");
            paintChip(bilingualChip, bilingual);
            applyBilingual();
        }));
        JPanel displayRow = new JPanel(new BorderLayout());
        displayRow.setOpaque(false);
        displayRow.add(bilingualChip, BorderLayout.WEST);

        moreRow.add(alphaRow);
        moreRow.add(Box.createVerticalStrut(6));
        moreRow.add(displayRow);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(header);
        top.add(actions);
        top.add(moreRow);

        // ---- 译文流 ----
        feed = new JPanel();
        feed.setOpaque(false);
        feed.setLayout(new BoxLayout(feed, BoxLayout.Y_AXIS));
        JPanel feedHolder = new JPanel(new BorderLayout());
        feedHolder.setOpaque(false);
        feedHolder.add(feed, BorderLayout.NORTH);
        scroll = new JScrollPane(feedHolder);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(24);

        JLabel grip = new JLabel("◢");
        grip.setForeground(new Color(255, 255, 255, 70));
        grip.setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
        grip.setToolTipText("拖动右下角调整大小");
        grip.setBorder(BorderFactory.createEmptyBorder(0, 0, -2, 0));
        MouseAdapter resizeHandler = new MouseAdapter() {
            private java.awt.Point start;
            private Dimension size;

            @Override
            public void mousePressed(MouseEvent event) {
                start = event.getPoint();
                size = frame.getSize();
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (start == null || size == null) return;
                maximized = false;
                int width = Math.max(320, size.width + event.getX() - start.x);
                int height = Math.max(240, size.height + event.getY() - start.y);
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
        south.setOpaque(false);
        south.add(grip, BorderLayout.EAST);

        root.add(top, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        frame.setContentPane(root);
    }

    // ---------------------------------------------------------------- 交互

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

    private void cycleDirection() {
        direction = "auto".equals(direction) ? "en2zh" : ("en2zh".equals(direction) ? "zh2en" : "auto");
        directionChip.setText(labelFor(direction));
        paintChip(directionChip, !"auto".equals(direction));
        host.onDirectionChanged(direction);
    }

    static String labelFor(String direction) {
        if ("en2zh".equals(direction)) return "英译中";
        if ("zh2en".equals(direction)) return "中译英";
        return "双向";
    }

    void setDirection(String value) {
        direction = value;
        directionChip.setText(labelFor(value));
        paintChip(directionChip, !"auto".equals(value));
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
            if (visible) frame.toFront();
        });
    }

    void toggleVisible() {
        SwingUtilities.invokeLater(() -> {
            frame.setVisible(!frame.isVisible());
            if (frame.isVisible()) frame.toFront();
        });
    }

    private void collapse() {
        collapsed = true;
        SwingUtilities.invokeLater(() -> {
            frame.setSize(64, 64);
            frame.setContentPane(bubble());
            frame.revalidate();
        });
    }

    void expand() {
        collapsed = false;
        SwingUtilities.invokeLater(() -> {
            frame.setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
            frame.setContentPane(root);
            root.revalidate();
            frame.toFront();
        });
    }

    boolean isCollapsed() {
        return collapsed;
    }

    private JPanel bubble() {
        JPanel panel = new JPanel(null) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(ACCENT);
                g2.fillOval(0, 0, getWidth() - 1, getHeight() - 1);
                g2.setColor(new Color(255, 255, 255, 200));
                g2.setStroke(new BasicStroke(2f));
                g2.drawOval(1, 1, getWidth() - 3, getHeight() - 3);
                g2.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setLayout(new BorderLayout());
        JLabel cat = new JLabel("🐱", JLabel.CENTER);
        cat.setFont(cat.getFont().deriveFont(26f));
        panel.add(cat, BorderLayout.CENTER);
        panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        DragHandler drag = new DragHandler();
        panel.addMouseListener(drag);
        panel.addMouseMotionListener(drag);
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (drag.moved) return;
                expand();
            }
        });
        return panel;
    }

    void toggleMaximize() {
        if (maximized) {
            frame.setBounds(restoreBounds == null ? new java.awt.Rectangle(frame.getLocation(), new Dimension(DEFAULT_WIDTH, DEFAULT_HEIGHT)) : restoreBounds);
            maximized = false;
        } else {
            restoreBounds = frame.getBounds();
            Rectangle screen = Screen.bounds();
            frame.setBounds(screen);
            maximized = true;
        }
        frame.validate();
    }

    private final class DragHandler extends MouseAdapter {
        private Point origin;
        private boolean moved;

        @Override
        public void mousePressed(MouseEvent event) {
            origin = event.getPoint();
            moved = false;
        }

        @Override
        public void mouseDragged(MouseEvent event) {
            if (origin == null) return;
            moved = true;
            Point location = frame.getLocation();
            frame.setLocation(location.x + event.getX() - origin.x, location.y + event.getY() - origin.y);
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
