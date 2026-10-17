package com.jimi.desktop;

import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/** 截屏、缩放、编码、画面指纹。Windows / Linux（X11、Wayland 走 XWayland）通用。 */
final class Screen {

    private Screen() {}

    static Rectangle bounds() {
        return new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
    }

    static BufferedImage grab() throws Exception {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("当前环境没有图形界面");
        return new Robot().createScreenCapture(bounds());
    }

    static BufferedImage scaled(BufferedImage source, int maxWidth) {
        if (source.getWidth() <= maxWidth) return source;
        int width = maxWidth;
        int height = Math.max(1, source.getHeight() * maxWidth / source.getWidth());
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return target;
    }

    static byte[] jpeg(BufferedImage source, int maxWidth, float quality) throws Exception {
        BufferedImage image = scaled(source, maxWidth);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256 * 1024);
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) throw new IllegalStateException("没有 JPEG 编码器");
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(buffer)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return buffer.toByteArray();
    }

    /** 32×24 灰度缩略图，用来判断画面有没有变化。 */
    static byte[] thumb(BufferedImage source) {
        BufferedImage small = new BufferedImage(32, 24, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, 32, 24, null);
        g.dispose();
        byte[] data = new byte[32 * 24];
        small.getRaster().getDataElements(0, 0, 32, 24, data);
        return data;
    }

    static boolean changed(byte[] before, byte[] after) {
        if (before == null || after == null || before.length != after.length) return true;
        long sum = 0;
        for (int i = 0; i < before.length; i++) {
            sum += Math.abs((before[i] & 0xFF) - (after[i] & 0xFF));
        }
        return sum / (double) before.length > 1.6;
    }

    static BufferedImage crop(BufferedImage source, Rectangle rect) {
        Rectangle safe = rect.intersection(new Rectangle(0, 0, source.getWidth(), source.getHeight()));
        if (safe.width < 8 || safe.height < 8) return source;
        BufferedImage target = new BufferedImage(safe.width, safe.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        g.drawImage(source, 0, 0, safe.width, safe.height, safe.x, safe.y, safe.x + safe.width,
            safe.y + safe.height, null);
        g.dispose();
        return target;
    }

    /** 在整屏图上画一个蓝框，告诉模型只翻这里（区域模式用来给上下文）。 */
    static BufferedImage markRegion(BufferedImage source, Rectangle rect) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.setColor(new java.awt.Color(30, 111, 232, 60));
        g.fillRect(rect.x, rect.y, rect.width, rect.height);
        g.setColor(new java.awt.Color(30, 111, 232));
        g.setStroke(new java.awt.BasicStroke(4f));
        g.drawRect(rect.x, rect.y, rect.width, rect.height);
        g.dispose();
        return copy;
    }
}
