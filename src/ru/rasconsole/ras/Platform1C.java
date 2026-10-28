package ru.rasconsole.ras;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Поиск установленных версий платформы 1С и их исполняемых файлов (1cv8c, 1cv8, rac).
 * Windows: {@code C:\Program Files\1cv8\<версия>\bin\*.exe}; Linux: {@code /opt/1cv8/<архитектура>/<версия>/*}.
 */
public final class Platform1C {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private Platform1C() {
    }

    /** Каталоги версий: C:\Program Files\1cv8\8.3.27.1989, /opt/1cv8/x86_64/8.3.27.1989 и т. п. */
    public static List<File> versions() {
        List<File> roots = new ArrayList<>();
        if (WINDOWS) {
            for (String root : new String[] {System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)")}) {
                if (root != null) roots.add(new File(root, "1cv8"));
            }
        } else {
            for (String arch : new String[] {"x86_64", "aarch64", "arm64", "i386"}) roots.add(new File("/opt/1cv8/" + arch));
        }
        List<File> r = new ArrayList<>();
        for (File root : roots) {
            File[] dirs = root.listFiles(f -> f.isDirectory() && f.getName().matches("\\d+(\\.\\d+)+"));
            if (dirs != null) r.addAll(List.of(dirs));
        }
        return r;
    }

    /** Исполняемый файл в каталоге версии (имя передаётся в виндовом виде: rac.exe, 1cv8c.exe). */
    private static File exeIn(File versionDir, String exe) {
        if (WINDOWS) return new File(versionDir, "bin" + File.separator + exe);
        String name = exe.endsWith(".exe") ? exe.substring(0, exe.length() - 4) : exe;
        return new File(versionDir, name);
    }

    /**
     * Найти исполняемый файл: сначала в версии, совпадающей с {@code preferVersion} (например, версия агента),
     * затем в самой новой 8.3, затем в самой новой любой.
     */
    public static Path find(String exe, String preferVersion) {
        List<File> candidates = new ArrayList<>();
        for (File v : versions()) {
            File f = exeIn(v, exe);
            if (f.isFile()) candidates.add(f);
        }
        if (candidates.isEmpty()) return null;
        if (preferVersion != null) {
            for (File f : candidates) {
                if (version(f).equals(preferVersion)) return f.toPath();
            }
        }
        candidates.sort(Comparator.comparing((File f) -> version(f).startsWith("8.3"))
            .thenComparing(f -> key(version(f))).reversed());
        return candidates.get(0).toPath();
    }

    /** Версия по пути файла: каталог над bin (Windows) или сам каталог файла (Linux). */
    public static String version(File exe) {
        File dir = exe.getParentFile();
        if (WINDOWS && dir != null && dir.getName().equalsIgnoreCase("bin")) dir = dir.getParentFile();
        return dir == null ? "" : dir.getName();
    }

    private static String key(String v) {
        StringBuilder sb = new StringBuilder();
        for (String part : v.split("\\.")) {
            try {
                sb.append(String.format("%06d", Integer.parseInt(part)));
            } catch (NumberFormatException e) {
                sb.append("000000");
            }
        }
        return sb.toString();
    }
}
