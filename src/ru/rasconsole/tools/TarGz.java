package ru.rasconsole.tools;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Архив .tar.gz для Linux-версии: в Windows нет удобного способа поставить файлам права на запуск,
 * поэтому tar пишется здесь (формат ustar), скриптам *.sh — права 755.
 * <p>
 * {@code java -cp out/classes ru.rasconsole.tools.TarGz <архив.tar.gz> <папка в архиве> <исходная папка>}
 */
public final class TarGz {

    private TarGz() {
    }

    public static void main(String[] a) throws IOException {
        if (a.length != 3) {
            System.err.println("TarGz <архив.tar.gz> <папка в архиве> <исходная папка>");
            System.exit(2);
        }
        Path src = Path.of(a[2]);
        List<Path> files;
        try (Stream<Path> s = Files.walk(src)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        long now = System.currentTimeMillis() / 1000;
        try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(Path.of(a[0])))) {
            dir(os, a[1] + "/", now);
            java.util.Set<String> dirs = new java.util.HashSet<>();
            for (Path f : files) {
                String rel = src.relativize(f).toString().replace('\\', '/');
                int slash = rel.lastIndexOf('/');
                if (slash > 0 && dirs.add(rel.substring(0, slash))) dir(os, a[1] + "/" + rel.substring(0, slash) + "/", now);
                byte[] data = Files.readAllBytes(f);
                int mode = rel.endsWith(".sh") ? 0755 : 0644;
                os.write(header(a[1] + "/" + rel, data.length, mode, '0', now));
                os.write(data);
                os.write(new byte[(int) ((512 - data.length % 512) % 512)]);
            }
            os.write(new byte[1024]);
        }
        System.out.println("Архив: " + a[0] + " (файлов: " + files.size() + ")");
    }

    private static void dir(OutputStream os, String name, long now) throws IOException {
        os.write(header(name, 0, 0755, '5', now));
    }

    private static byte[] header(String name, long size, int mode, char type, long mtime) throws IOException {
        byte[] h = new byte[512];
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        if (n.length > 100) {
            // длинное имя: начало — в поле prefix (155 байт), разрез по «/»
            int cut = name.lastIndexOf('/', Math.min(name.length() - 1, 155));
            byte[] pre = name.substring(0, cut).getBytes(StandardCharsets.UTF_8);
            n = name.substring(cut + 1).getBytes(StandardCharsets.UTF_8);
            if (pre.length > 155 || n.length > 100) throw new IOException("слишком длинное имя: " + name);
            System.arraycopy(pre, 0, h, 345, pre.length);
        }
        System.arraycopy(n, 0, h, 0, n.length);
        octal(h, 100, 8, mode);
        octal(h, 108, 8, 0);
        octal(h, 116, 8, 0);
        octal(h, 124, 12, size);
        octal(h, 136, 12, mtime);
        for (int i = 148; i < 156; i++) h[i] = ' ';
        h[156] = (byte) type;
        byte[] magic = "ustar\u000000".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, h, 257, magic.length);
        byte[] owner = "root".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(owner, 0, h, 265, owner.length);
        System.arraycopy(owner, 0, h, 297, owner.length);
        long sum = 0;
        for (byte b : h) sum += b & 0xff;
        String cs = String.format("%06o", sum);
        System.arraycopy(cs.getBytes(StandardCharsets.US_ASCII), 0, h, 148, 6);
        h[154] = 0;
        h[155] = ' ';
        return h;
    }

    private static void octal(byte[] h, int off, int len, long v) {
        String s = String.format("%0" + (len - 1) + "o", v);
        System.arraycopy(s.getBytes(StandardCharsets.US_ASCII), 0, h, off, len - 1);
        h[off + len - 1] = 0;
    }
}
