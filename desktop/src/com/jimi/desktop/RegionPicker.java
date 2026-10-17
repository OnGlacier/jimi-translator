package com.jimi.desktop;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JWindow;

/** 全屏框选：拖一个矩形，回车确认，Esc 取消。 */
final class RegionPicker {

    private RegionPicker() {}

    static void pick(Consumer<Rectangle> callback) {
        if (GraphicsEnvironment.isHeadless()) return;
        JWindow window = new JWindow();
        window.setAlwaysOnTop(true);
        window.setBounds(Screen.bounds());
        boolean translucent = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getDefaultScreenDevice().isWindowTranslucencySupported(
                java.awt.GraphicsDevice.WindowTranslucency.TRANSLUCENT);
        if (translucent) {
            window.setBackground(new Color(0, 0, 0, 0));
        }

        JComponent canvas = new JComponent() {
            Point start;
            Point end;

            {
                setOpaque(false);
                setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                MouseAdapter adapter = new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent event) {
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
                        end = event.getPoint();
                        repaint();
                        Rectangle rect = selection();
                        if (rect.width < 24 || rect.height < 24) return;
                        window.setVisible(false);
                        window.dispose();
                        callback.accept(rect);
                    }
                };
                addMouseListener(adapter);
                addMouseMotionListener(adapter);
            }

            Rectangle selection() {
                if (start == null || end == null) return new Rectangle();
                int x = Math.min(start.x, end.x);
                int y = Math.min(start.y, end.y);
                return new Rectangle(x, y, Math.abs(start.x - end.x), Math.abs(start.y - end.y));
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0, 0, 0, 96));
                g2.fillRect(0, 0, getWidth(), getHeight());
                Rectangle rect = selection();
                if (rect.width > 0 && rect.height > 0) {
                    g2.setColor(new Color(245, 158, 11, 40));
                    g2.fillRect(rect.x, rect.y, rect.width, rect.height);
                    g2.setColor(new Color(245, 158, 11));
                    g2.setStroke(new BasicStroke(2f));
                    g2.drawRect(rect.x, rect.y, rect.width, rect.height);
                }
                g2.setColor(new Color(255, 255, 255, 200));
                g2.setFont(g2.getFont().deriveFont(14f));
                g2.drawString("拖动鼠标框选要翻译的区域 · 松开完成 · Esc 取消", 24, 32);
                g2.dispose();
            }
        };

        window.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    window.setVisible(false);
                    window.dispose();
                }
            }
        });

        window.setContentPane(canvas);
        window.setVisible(true);
        window.requestFocus();
    }
}
