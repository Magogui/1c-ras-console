package ru.rasconsole.tools;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Генератор иконки консоли: тёмно-синий скруглённый квадрат, три серверных блока со светодиодами
 * состояния и оранжевая шестерёнка администрирования. Рисуется векторно для каждого размера.
 * <pre>java -cp ras-console.jar ru.rasconsole.tools.IconGen &lt;папка ресурсов&gt; &lt;файл .ico&gt;</pre>
 */
public final class IconGen {

    private static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    public static void main(String[] args) throws IOException {
        Path pngDir = Path.of(args[0]);
        Path ico = Path.of(args[1]);
        Files.createDirectories(pngDir);
        List<byte[]> pngs = new ArrayList<>();
        for (int s : SIZES) {
            BufferedImage img = draw(s);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ImageIO.write(img, "png", b);
            pngs.add(b.toByteArray());
            if (s == 16 || s == 24 || s == 32 || s == 48 || s == 64 || s == 128 || s == 256) {
                Files.write(pngDir.resolve("app-" + s + ".png"), b.toByteArray());
            }
        }
        writeIco(ico, pngs);
        System.out.println("PNG: " + pngDir + ", ICO: " + ico);
    }

    /** Иконка размера s×s. */
    static BufferedImage draw(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double u = s / 256.0; // все координаты — в сетке 256×256

        // фон: скруглённый квадрат с вертикальным градиентом
        RoundRectangle2D bg = new RoundRectangle2D.Double(8 * u, 8 * u, 240 * u, 240 * u, 56 * u, 56 * u);
        g.setPaint(new GradientPaint(0, (float) (8 * u), new Color(0x2f5d9a), 0, (float) (248 * u), new Color(0x183766)));
        g.fill(bg);
        if (s >= 32) {
            g.setColor(new Color(255, 255, 255, 40));
            g.setStroke(new BasicStroke((float) Math.max(1, 3 * u)));
            g.draw(bg);
        }

        // три серверных блока
        boolean tiny = s <= 20;
        int blocks = 3;
        double bx = 40 * u;
        double bw = 150 * u;
        double bh = tiny ? 44 * u : 40 * u;
        double gap = tiny ? 16 * u : 14 * u;
        double by = tiny ? 48 * u : 50 * u;
        Color[] leds = {new Color(0x5ce06a), new Color(0x5ce06a), new Color(0xffc531)};
        for (int i = 0; i < blocks; i++) {
            double y = by + i * (bh + gap);
            RoundRectangle2D blk = new RoundRectangle2D.Double(bx, y, bw, bh, 12 * u, 12 * u);
            g.setColor(new Color(0xe8eef7));
            g.fill(blk);
            if (!tiny) {
                // щели вентиляции
                g.setColor(new Color(0x9fb3cf));
                for (int k = 0; k < 3; k++) {
                    double sx = bx + (18 + k * 16) * u;
                    g.fill(new RoundRectangle2D.Double(sx, y + 11 * u, 8 * u, bh - 22 * u, 4 * u, 4 * u));
                }
            }
            // светодиод
            double r = (tiny ? 13 : 11) * u;
            g.setColor(leds[i]);
            g.fill(new Ellipse2D.Double(bx + bw - 30 * u - r, y + bh / 2 - r, 2 * r, 2 * r));
        }

        // шестерёнка администрирования в правом нижнем углу
        double cx = 182 * u;
        double cy = 182 * u;
        double rOuter = (tiny ? 58 : 54) * u;
        double rInner = rOuter * 0.74;
        double rHole = rOuter * 0.34;
        int teeth = 8;
        Path2D gear = new Path2D.Double();
        for (int i = 0; i < teeth * 2; i++) {
            double a0 = Math.PI * 2 * i / (teeth * 2);
            double a1 = Math.PI * 2 * (i + 1) / (teeth * 2);
            double rr = i % 2 == 0 ? rOuter : rInner;
            // зуб: дуга по радиусу rr с небольшим скосом
            double skew = (a1 - a0) * 0.18;
            double[] angles = {a0 + skew, a1 - skew};
            for (double a : angles) {
                double x = cx + Math.cos(a) * rr;
                double y = cy + Math.sin(a) * rr;
                if (gear.getCurrentPoint() == null) gear.moveTo(x, y);
                else gear.lineTo(x, y);
            }
        }
        gear.closePath();
        Area gearArea = new Area(gear);
        gearArea.subtract(new Area(new Ellipse2D.Double(cx - rHole, cy - rHole, 2 * rHole, 2 * rHole)));
        // обводка цветом фона, чтобы шестерёнка «отрывалась» от серверов
        g.setColor(new Color(0x183766));
        g.setStroke(new BasicStroke((float) Math.max(1.2, 14 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(gearArea);
        g.setPaint(new GradientPaint((float) (cx - rOuter), (float) (cy - rOuter), new Color(0xffb02e),
            (float) (cx + rOuter), (float) (cy + rOuter), new Color(0xf07a12)));
        g.fill(gearArea);
        g.dispose();
        return img;
    }

    /** ICO с PNG-кадрами (Windows Vista и новее). */
    private static void writeIco(Path file, List<byte[]> pngs) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        writeShortLE(out, 0);
        writeShortLE(out, 1);
        writeShortLE(out, pngs.size());
        int offset = 6 + 16 * pngs.size();
        for (int i = 0; i < pngs.size(); i++) {
            int s = SIZES[i];
            out.writeByte(s >= 256 ? 0 : s);
            out.writeByte(s >= 256 ? 0 : s);
            out.writeByte(0);
            out.writeByte(0);
            writeShortLE(out, 1);
            writeShortLE(out, 32);
            writeIntLE(out, pngs.get(i).length);
            writeIntLE(out, offset);
            offset += pngs.get(i).length;
        }
        for (byte[] p : pngs) out.write(p);
        out.flush();
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, b.toByteArray());
    }

    private static void writeShortLE(DataOutputStream o, int v) throws IOException {
        o.writeByte(v & 0xff);
        o.writeByte((v >> 8) & 0xff);
    }

    private static void writeIntLE(DataOutputStream o, int v) throws IOException {
        o.writeByte(v & 0xff);
        o.writeByte((v >> 8) & 0xff);
        o.writeByte((v >> 16) & 0xff);
        o.writeByte((v >> 24) & 0xff);
    }
}
