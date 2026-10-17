package com.jimi.desktop;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * 全屏框选。
 *
 * 用带焦点的 JFrame（不是 JWindow），保证能收键盘、能 Esc 取消；右键 / 点一下不拖 也取消。
 * 没合成器时退化成半透明黑仍然可用，不会卡在灰屏上。
 */
final class RegionPicker {

    private RegionPicker() {}

    static void pick(Consumer<Rectangle> callback) {
        if (GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeLater(() -> show(callback));
    }

    private static void show(Consumer<Rectangle> callback) {
        Rectangle screen = Screen.bounds();
        boolean translucent = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getDefaultScreenDevice().isWindowTranslucencySupported(
                GraphicsDevice.WindowTranslucency.TRANSLUCENT);

        JFrame window = new JFrame("框选翻译区域");
        window.setUndecorated(true);
        window.setAlwaysOnTop(true);
        window.setBounds(screen);
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        window.setFocusableWindowState(true);
        if (translucent) {
            window.setBackground(new Color(0, 0, 0, 0));
        }

        JComponent canvas = new JComponent() {
            private Point start;
            private Point end;

            {
                setOpaque(false);
                setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                MouseAdapter adapter = new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent event) {
                        if (event.isPopupTrigger() || event.getButton() == MouseEvent.BUTTON3) {
                            close(window, null, callback);
                            return;
                        }
                        start = event.getPoint();
                        end = start;
                        repaint();
                    }

                    @Override
                    public void mouseDragged(MouseEvent event) {
                        end = event.getPoint();
                        repaint();
                    }

                    @Override
                    public void mouseReleased(MouseEvent event) {
                        if (start == null || end == null) return;
                        Rectangle rect = normalize(start, end);
                        // 点一下没拖动 = 取消，避免「点了一下就灰屏卡住」
                        if (rect.width < 24 || rect.height < 24) {
                            close(window, null, callback);
                            return;
                        }
                        close(window, rect, callback);
                    }
                };
                addMouseListener(adapter);
                addMouseMotionListener(adapter);
            }

            @Override
            protected void paintComponent(Graphics g) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0, 0, 0, 110));
                g2.fillRect(0, 0, getWidth(), getHeight());
                if (start != null && end != null) {
                    Rectangle rect = normalize(start, end);
                    if (rect.width > 0 && rect.height > 0) {
                        g2.setColor(new Color(245, 158, 11, 45));
                        g2.fillRect(rect.x, rect.y, rect.width, rect.height);
                        g2.setColor(new Color(245, 158, 11));
                        g2.setStroke(new BasicStroke(2f));
                        g2.drawRect(rect.x, rect.y, rect.width, rect.height);
                    }
                }
                g2.setColor(new Color(255, 255, 255, 230));
                g2.setFont(g2.getFont().deriveFont(14f));
                g2.drawString("拖动鼠标框选要翻译的区域 · 松开完成 · Esc / 右键取消", 24, 34);
                g2.dispose();
            }
        };

        JPanel content = new JPanel(new BorderLayout());
        content.setOpaque(false);
        content.add(canvas, BorderLayout.CENTER);

        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        JLabel cancel = new JLabel(" 取消 (Esc) ");
        cancel.setOpaque(true);
        cancel.setBackground(new Color(0, 0, 0, 160));
        cancel.setForeground(new Color(255, 255, 255, 220));
        cancel.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        cancel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        cancel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                close(window, null, callback);
            }
        });
        bar.add(cancel, BorderLayout.EAST);
        content.add(bar, BorderLayout.SOUTH);

        window.setContentPane(content);

        window.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        window.getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                close(window, null, callback);
            }
        });

        window.setVisible(true);
        window.toFront();
        window.requestFocus();
        canvas.requestFocusInWindow();
    }

    private static Rectangle normalize(Point a, Point b) {
        int x = Math.min(a.x, b.x);
        int y = Math.min(a.y, b.y);
        return new Rectangle(x, y, Math.abs(a.x - b.x), Math.abs(a.y - b.y));
    }

    private static void close(JFrame window, Rectangle rect, Consumer<Rectangle> callback) {
        window.setVisible(false);
        window.dispose();
        if (callback != null) {
            // 取消也回调（rect = null），调用方好把浮窗恢复回来
            SwingUtilities.invokeLater(() -> callback.accept(rect));
        }
    }
}
