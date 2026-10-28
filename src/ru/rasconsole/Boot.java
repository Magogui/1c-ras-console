package ru.rasconsole;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Запуск с библиотекой 1С, которая не входит в публичную сборку.
 * <p>
 * Java API сервера администрирования (© ООО «1С-Софт») разрешено использовать только авторизованным
 * пользователям «1С:Предприятия 8», поэтому в публичную сборку jar-файлы 1С не кладутся. При первом запуске
 * пользователь даёт консоли архив, скачанный с ИТС: консоль находит его сама (рядом с программой или
 * в «Загрузках») или просит выбрать. jar-файлы извлекаются в профиль пользователя ({@code api}) и при каждом
 * запуске подключаются отдельным загрузчиком классов.
 * <p>
 * Этот класс не должен ссылаться ни на классы 1С, ни на остальные классы консоли — они загружаются
 * уже после того, как библиотека найдена.
 */
public final class Boot {

    /** Класс, по которому проверяется, что библиотека 1С доступна. */
    private static final String PROBE = "com._1c.v8.ibis.admin.IAgentAdminConnection";
    /** Главный jar библиотеки — без него извлечение считается неудачным. */
    private static final String MAIN_JAR = "com._1c.v8.ibis.admin-";
    static final String ITS_URL = "https://its.1c.ru/db/content/metod8dev/src/admins/i8104985.htm";

    private Boot() {
    }

    /** Окно консоли. */
    public static void main(String[] args) {
        run("ru.rasconsole.Launcher", args, true);
    }

    /**
     * Запустить {@code mainClass} так, чтобы ему была доступна библиотека 1С.
     * @param gui окно (диалоги Swing) или командная строка (сообщения в консоль)
     */
    static void run(String mainClass, String[] args, boolean gui) {
        try {
            ClassLoader loader = loader(gui);
            if (loader == null) System.exit(gui ? 0 : 2);
            Thread.currentThread().setContextClassLoader(loader);
            Method m = Class.forName(mainClass, true, loader).getMethod("main", String[].class);
            m.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof RuntimeException r) throw r;
            if (t instanceof Error er) throw er;
            throw new IllegalStateException(t);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Не удалось запустить " + mainClass + ": " + e, e);
        }
    }

    /** Загрузчик, видящий библиотеку 1С; null — пользователь отказался её указать. */
    private static ClassLoader loader(boolean gui) {
        ClassLoader own = Boot.class.getClassLoader();
        // сборка с библиотекой (lib/ на classpath) — ничего делать не нужно
        if (available(own)) return own;
        Path api = apiDir();
        if (!hasApi(api)) {
            Path found = findArchive();
            if (found != null) {
                try {
                    install(found, api);
                    say(gui, "Библиотека 1С извлечена из " + found + " в " + api);
                } catch (IOException e) {
                    say(gui, "Не удалось извлечь библиотеку 1С из " + found + ": " + e.getMessage());
                }
            }
        }
        while (!hasApi(api)) {
            Path chosen = gui ? BootDialog.askArchive(api) : null;
            if (chosen == null) {
                if (!gui) printHelp(con(System.err), api);
                return null;
            }
            try {
                install(chosen, api);
            } catch (IOException e) {
                BootDialog.error("Не удалось извлечь библиотеку 1С из «" + chosen.getFileName() + "»:\n" + e.getMessage());
            }
        }
        return childFirst(api, own);
    }

    private static boolean available(ClassLoader cl) {
        try {
            Class.forName(PROBE, false, cl);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** Каталог настроек консоли: %APPDATA%\RasConsole или ~/.rasconsole (как в ServerStore). */
    static Path settingsDir() {
        String appData = System.getenv("APPDATA");
        return appData != null ? Path.of(appData, "RasConsole") : Path.of(System.getProperty("user.home"), ".rasconsole");
    }

    /** Куда извлекается библиотека 1С. */
    public static Path apiDir() {
        return settingsDir().resolve("api");
    }

    static boolean hasApi(Path dir) {
        return !jars(dir).isEmpty() && jars(dir).stream().anyMatch(p -> p.getFileName().toString().startsWith(MAIN_JAR));
    }

    private static List<Path> jars(Path dir) {
        List<Path> r = new ArrayList<>();
        if (!Files.isDirectory(dir)) return r;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path p : ds) r.add(p);
        } catch (IOException ignored) {
            // каталог недоступен — как будто библиотеки нет
        }
        return r;
    }

    /**
     * Загрузчик «сначала свои»: классы консоли, 1С и netty берутся из своих jar-файлов, остальное (JDK, JavaFX) —
     * у родителя. Иначе классы консоли загрузил бы родитель, а он библиотеку 1С не видит.
     */
    private static ClassLoader childFirst(Path api, ClassLoader parent) {
        List<URL> urls = new ArrayList<>();
        try {
            urls.add(Boot.class.getProtectionDomain().getCodeSource().getLocation());
            for (Path p : jars(api)) urls.add(p.toUri().toURL());
        } catch (IOException | SecurityException e) {
            throw new IllegalStateException("Библиотека 1С: " + e.getMessage(), e);
        }
        return new URLClassLoader("rasconsole", urls.toArray(new URL[0]), parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                boolean own = (name.startsWith("ru.rasconsole.") && !name.equals(Boot.class.getName()))
                    || name.startsWith("com._1c.") || name.startsWith("org.jboss.netty.");
                if (!own) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        try {
                            c = findClass(name);
                        } catch (ClassNotFoundException e) {
                            c = super.loadClass(name, false);
                        }
                    }
                    if (resolve) resolveClass(c);
                    return c;
                }
            }
        };
    }

    // ---- поиск и извлечение архива ----

    /** Архив Java API рядом с программой (и на уровень выше — рядом с exe) или в «Загрузках». */
    static Path findArchive() {
        List<Path> dirs = new ArrayList<>();
        try {
            Path jar = Path.of(Boot.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path d = Files.isDirectory(jar) ? jar : jar.getParent();
            for (int i = 0; i < 3 && d != null; i++, d = d.getParent()) dirs.add(d);
        } catch (Exception ignored) {
            // нет пути к программе — ищем только в «Загрузках»
        }
        dirs.add(Path.of(System.getProperty("user.home"), "Downloads"));
        dirs.add(Path.of(System.getProperty("user.home"), "Загрузки"));
        for (Path d : dirs) {
            if (!Files.isDirectory(d)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "*.zip")) {
                for (Path p : ds) if (looksLikeApi(p.getFileName().toString())) return p;
            } catch (IOException ignored) {
                // нет доступа к папке
            }
        }
        return null;
    }

    static boolean looksLikeApi(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".zip") && (n.contains("java-api") || n.startsWith("com._1c.v8.ibis.admin"));
    }

    /**
     * Извлечь jar-файлы из архива с ИТС (архив может быть вложен в архив). Берутся только файлы {@code lib/*.jar}
     * и только под своим именем — пути внутри архива на диск не переносятся.
     */
    public static void install(Path archive, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        deleteDir(tmp);
        Files.createDirectories(tmp);
        int[] count = {0};
        try (InputStream in = Files.newInputStream(archive)) {
            scan(new ZipInputStream(in), tmp, count, 0);
        } catch (IllegalArgumentException e) {
            deleteDir(tmp);
            throw new IOException("файл не похож на zip-архив: " + e.getMessage());
        }
        if (!hasApi(tmp)) {
            deleteDir(tmp);
            throw new IOException("в архиве нет библиотеки " + MAIN_JAR + "*.jar. Нужен «Программный Java-интерфейс для административного"
                + " сервера» с ИТС (вариант для 8.3.11 и старше).");
        }
        deleteDir(target);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void scan(ZipInputStream zin, Path out, int[] count, int depth) throws IOException {
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
            if (e.isDirectory()) continue;
            String name = e.getName().replace('\\', '/');
            String base = name.substring(name.lastIndexOf('/') + 1);
            if (name.toLowerCase(Locale.ROOT).endsWith(".zip") && depth < 3) {
                // вложенный архив читаем прямо из потока, не закрывая внешний
                scan(new ZipInputStream(new FilterInputStream(zin) {
                    @Override
                    public void close() {
                        // внешний поток закроет вызывающий
                    }
                }), out, count, depth + 1);
            } else if (base.toLowerCase(Locale.ROOT).endsWith(".jar") && name.contains("lib/") && !base.isEmpty()
                && base.matches("[A-Za-z0-9._+-]+")) {
                Files.copy(zin, out.resolve(base), StandardCopyOption.REPLACE_EXISTING);
                count[0]++;
            }
        }
    }

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static void say(boolean gui, String text) {
        if (!gui) con(System.err).println(text);
    }

    /** Вывод в кодировке консоли (как в Cli), а при перенаправлении в файл — в UTF-8. */
    private static PrintStream con(PrintStream base) {
        java.nio.charset.Charset cs = System.console() != null ? System.console().charset() : StandardCharsets.UTF_8;
        return new PrintStream(base, true, cs);
    }

    static void printHelp(PrintStream out, Path api) {
        out.println("""
            Не найдена библиотека 1С «Программный Java-интерфейс для административного сервера».
            Она не входит в публичную сборку: пользоваться ею могут только пользователи «1С:Предприятия 8».

            1. Скачайте архив с ИТС (вариант для 8.3.11 и старше):
               %s
            2. Установите его одной из команд:
               RasCli install-api <путь к архиву .zip>
               или положите архив рядом с программой и запустите её снова.

            Библиотека будет извлечена в %s
            """.formatted(ITS_URL, api));
    }

    /** {@code RasCli install-api архив.zip} — установить библиотеку без окна. */
    static int installCommand(String[] args) {
        PrintStream out = con(System.out);
        if (args.length < 2) {
            out.println("Использование: RasCli install-api <архив с ИТС .zip>");
            return 2;
        }
        Path archive = Path.of(args[1]);
        if (!Files.isRegularFile(archive)) {
            out.println("Нет файла: " + archive);
            return 1;
        }
        try {
            install(archive, apiDir());
            out.println("Библиотека 1С установлена: " + apiDir());
            for (Path p : jars(apiDir())) out.println("  " + p.getFileName());
            return 0;
        } catch (IOException e) {
            out.println("Ошибка: " + e.getMessage());
            return 1;
        }
    }

    /** Для «Справки»: где лежит библиотека. */
    public static String apiLocation() {
        return available(ClassLoader.getSystemClassLoader()) ? "в составе сборки (lib)" : apiDir().toString();
    }

}
