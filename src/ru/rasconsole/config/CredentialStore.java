package ru.rasconsole.config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import ru.rasconsole.ras.Credentials;
import ru.rasconsole.ras.RasConnection;

/**
 * Сохранённые пароли администраторов, зашифрованные ключом текущего пользователя.
 * <p>
 * Windows: DPAPI — стандартный .NET-класс {@code ProtectedData} через PowerShell, поэтому
 * дополнительные библиотеки не нужны. Данные передаются через stdin, а не в командной строке.
 * Файл {@code credentials.dat} нельзя расшифровать на другом компьютере или под другим пользователем.
 * <p>
 * Linux: AES-GCM, случайный ключ хранится в связке ключей пользователя (Secret Service: GNOME Keyring, KWallet)
 * через утилиту {@code secret-tool} из пакета libsecret-tools. Без неё сохранение паролей недоступно.
 * <p>
 * Ключи: {@code agent|host:port}, {@code cluster|host:port|clusterId},
 * {@code ib|host:port|clusterId|user}. Значение — {@code user\0password}.
 */
public final class CredentialStore {

    private static final String HEADER = "RasConsole-DPAPI-1";
    private static final String HEADER_AES = "RasConsole-AESGCM-1";
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    /** Дополнительная энтропия DPAPI: чужая программа, вызвавшая DPAPI без неё, файл не расшифрует. */
    private static final String ENTROPY = "RasConsole.credentials.v1";

    private final Path file;
    private final Map<String, String> data = new TreeMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "credential-store");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean available = true;
    private volatile String lastError;

    public CredentialStore(Path dir) {
        this.file = dir.resolve("credentials.dat");
    }

    public Path file() {
        return file;
    }

    /** false — DPAPI недоступен (не Windows или PowerShell не запустился); сохранять пароли нельзя. */
    public boolean available() {
        return available;
    }

    public String lastError() {
        return lastError;
    }

    /** Прочитать файл (блокирующий вызов, около полсекунды из-за запуска PowerShell). */
    public synchronized void load() {
        data.clear();
        if (!WINDOWS && !SecretTool.present()) {
            available = false;
            lastError = "нет утилиты secret-tool (пакет libsecret-tools) — пароли не сохраняются";
            return;
        }
        if (!Files.exists(file)) return;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.US_ASCII);
            String expected = WINDOWS ? HEADER : HEADER_AES;
            if (lines.size() < 2 || !expected.equals(lines.get(0).trim())) {
                lastError = "Неизвестный формат " + file;
                return;
            }
            byte[] plain = crypt(false, Base64.getDecoder().decode(lines.get(1).trim()));
            Properties p = new Properties();
            p.load(new StringReader(new String(plain, StandardCharsets.UTF_8)));
            for (String k : p.stringPropertyNames()) data.put(k, p.getProperty(k));
        } catch (Exception e) {
            lastError = "Не удалось расшифровать сохранённые пароли: " + e.getMessage();
            System.err.println(lastError);
        }
    }

    // ---- чтение ----

    public synchronized Credentials agent(String address) {
        return decode(data.get("agent|" + address));
    }

    public synchronized boolean hasCluster(String address, UUID clusterId) {
        return data.containsKey("cluster|" + address + "|" + clusterId);
    }

    /** Передать соединению все сохранённые для его адреса учётные данные. */
    public synchronized void applyTo(RasConnection conn) {
        String address = conn.config().address();
        Credentials a = agent(address);
        if (a != null) conn.setAgentCredentials(a);
        for (Map.Entry<String, String> e : data.entrySet()) {
            String[] k = e.getKey().split("\\|", 4);
            if (k.length < 3 || !k[1].equals(address)) continue;
            UUID cid;
            try {
                cid = UUID.fromString(k[2]);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            Credentials c = decode(e.getValue());
            if (c == null) continue;
            if (k[0].equals("cluster")) conn.setClusterCredentials(cid, c);
            else if (k[0].equals("ib")) conn.addInfoBaseCredentials(cid, c);
        }
    }

    // ---- запись (файл перезаписывается в фоне) ----

    public void putAgent(String address, Credentials c) {
        put("agent|" + address, c);
    }

    public void putCluster(String address, UUID clusterId, Credentials c) {
        put("cluster|" + address + "|" + clusterId, c);
    }

    public void putInfoBase(String address, UUID clusterId, Credentials c) {
        put("ib|" + address + "|" + clusterId + "|" + c.user(), c);
    }

    /** Произвольный секрет (например, пароль СУБД в шаблоне ИБ); ключ — с префиксом, не совпадающим с серверными. */
    public void putSecret(String key, Credentials c) {
        put("secret|" + key, c);
    }

    public synchronized Credentials secret(String key) {
        return decode(data.get("secret|" + key));
    }

    public synchronized void removeSecret(String key) {
        if (data.remove("secret|" + key) != null) saveAsync();
    }

    /** Удалить все пароли сервера (при удалении сервера из списка). */
    public synchronized void removeServer(String address) {
        if (data.keySet().removeIf(k -> {
            String[] p = k.split("\\|", 3);
            return p.length > 1 && p[1].equals(address);
        })) {
            saveAsync();
        }
    }

    /** Перенести пароли при смене адреса сервера. */
    public synchronized void renameServer(String oldAddress, String newAddress) {
        if (oldAddress.equals(newAddress)) return;
        Map<String, String> moved = new HashMap<>();
        data.entrySet().removeIf(e -> {
            String[] k = e.getKey().split("\\|", 3);
            if (k.length > 1 && k[1].equals(oldAddress)) {
                moved.put(k[0] + "|" + newAddress + (k.length > 2 ? "|" + k[2] : ""), e.getValue());
                return true;
            }
            return false;
        });
        if (!moved.isEmpty()) {
            data.putAll(moved);
            saveAsync();
        }
    }

    public synchronized int size() {
        return data.size();
    }

    /** Забыть все сохранённые пароли. */
    public synchronized void clear() {
        data.clear();
        writer.execute(() -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                lastError = e.getMessage();
            }
        });
    }

    private synchronized void put(String key, Credentials c) {
        if (!available) return;
        data.put(key, c.user() + "\0" + c.password());
        saveAsync();
    }

    private void saveAsync() {
        Map<String, String> snapshot = new TreeMap<>(data);
        writer.execute(() -> {
            try {
                Properties p = new Properties();
                p.putAll(snapshot);
                StringWriter sw = new StringWriter();
                p.store(sw, null);
                byte[] enc = crypt(true, sw.toString().getBytes(StandardCharsets.UTF_8));
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, (WINDOWS ? HEADER : HEADER_AES) + "\r\n" + Base64.getEncoder().encodeToString(enc) + "\r\n",
                    StandardCharsets.US_ASCII);
                if (!WINDOWS) {
                    try {
                        Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                    } catch (UnsupportedOperationException ignored) {
                        // файловая система без прав POSIX
                    }
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                lastError = "Не удалось сохранить пароли: " + e.getMessage();
                System.err.println(lastError);
            }
        });
    }

    /** Дождаться записи (при выходе из программы). */
    public void flush() {
        writer.shutdown();
        try {
            writer.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static Credentials decode(String v) {
        if (v == null) return null;
        int i = v.indexOf('\0');
        return i < 0 ? new Credentials(v, "") : new Credentials(v.substring(0, i), v.substring(i + 1));
    }

    /** Зашифровать или расшифровать: DPAPI в Windows, AES-GCM с ключом из связки ключей в Linux. */
    private byte[] crypt(boolean protect, byte[] input) throws Exception {
        return WINDOWS ? dpapi(protect, input) : aes(protect, input);
    }

    // ---- Linux: AES-GCM + Secret Service ----

    private static final String KEY_ATTR = "credentials-v1";

    private byte[] aes(boolean protect, byte[] input) throws Exception {
        String k = SecretTool.lookup(KEY_ATTR);
        if (k == null || k.isBlank()) {
            if (!protect) throw new IOException("ключ шифрования не найден в связке ключей (secret-tool)");
            byte[] key = new byte[32];
            new java.security.SecureRandom().nextBytes(key);
            k = Base64.getEncoder().encodeToString(key);
            SecretTool.store(KEY_ATTR, k);
        }
        javax.crypto.spec.SecretKeySpec key = new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(k.trim()), "AES");
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        if (protect) {
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] enc = c.doFinal(input);
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return out;
        }
        c.init(javax.crypto.Cipher.DECRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, input, 0, 12));
        return c.doFinal(input, 12, input.length - 12);
    }

    /** Утилита secret-tool (libsecret): секрет передаётся через stdin/stdout, а не в командной строке. */
    static final class SecretTool {
        private SecretTool() {
        }

        private static volatile String exe;

        static boolean present() {
            for (String dir : System.getenv().getOrDefault("PATH", "/usr/bin:/bin").split(java.io.File.pathSeparator)) {
                // только абсолютные каталоги: «.» и пустые элементы PATH пропускаются
                if (dir.isEmpty() || !dir.startsWith("/")) continue;
                Path p = Path.of(dir, "secret-tool");
                if (Files.isExecutable(p)) {
                    exe = p.toString();
                    return true;
                }
            }
            return false;
        }

        private static String exe() throws IOException {
            if (exe == null && !present()) throw new IOException("нет утилиты secret-tool");
            return exe;
        }

        static String lookup(String attr) throws IOException, InterruptedException {
            Process p = new ProcessBuilder(exe(), "lookup", "application", "rasconsole", "key", attr).start();
            p.getOutputStream().close();
            byte[] out = readAll(p.getInputStream());
            readAll(p.getErrorStream());
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("secret-tool не ответил за 30 с");
            }
            return p.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8) : null;
        }

        static void store(String attr, String secret) throws IOException, InterruptedException {
            Process p = new ProcessBuilder(exe(), "store", "--label=RasConsole: ключ сохранённых паролей",
                "application", "rasconsole", "key", attr).start();
            try (OutputStream os = p.getOutputStream()) {
                os.write(secret.getBytes(StandardCharsets.UTF_8));
            }
            readAll(p.getInputStream());
            byte[] err = readAll(p.getErrorStream());
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("secret-tool не ответил за 30 с");
            }
            if (p.exitValue() != 0) throw new IOException("secret-tool: " + new String(err, StandardCharsets.UTF_8).trim());
        }
    }

    // ---- DPAPI ----

    /**
     * Зашифровать или расшифровать байты DPAPI (CurrentUser) через PowerShell.
     * Скрипт передаётся в -EncodedCommand, данные — в base64 через stdin.
     */
    private byte[] dpapi(boolean protect, byte[] input) throws IOException, InterruptedException {
        String script = "$ErrorActionPreference='Stop';"
            + "Add-Type -AssemblyName System.Security;"
            + "$e=[Text.Encoding]::UTF8.GetBytes('" + ENTROPY + "');"
            + "$in=[Convert]::FromBase64String([Console]::In.ReadToEnd().Trim());"
            + "$out=[Security.Cryptography.ProtectedData]::" + (protect ? "Protect" : "Unprotect")
            + "($in,$e,[Security.Cryptography.DataProtectionScope]::CurrentUser);"
            + "[Console]::Out.Write([Convert]::ToBase64String($out))";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        List<String> cmd = new ArrayList<>(List.of(systemExe("System32\\WindowsPowerShell\\v1.0\\powershell.exe"),
            "-NoProfile", "-NonInteractive",
            "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded));
        Process p;
        try {
            p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.PIPE).start();
        } catch (IOException e) {
            available = false;
            throw e;
        }
        try (OutputStream os = p.getOutputStream()) {
            os.write(Base64.getEncoder().encode(input));
        }
        byte[] out = readAll(p.getInputStream());
        byte[] err = readAll(p.getErrorStream());
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("PowerShell не ответил за 30 с");
        }
        if (p.exitValue() != 0) {
            throw new IOException("DPAPI: " + new String(err, StandardCharsets.UTF_8).trim());
        }
        return Base64.getDecoder().decode(new String(out, StandardCharsets.US_ASCII).trim());
    }

    /**
     * Полный путь к программе Windows. Windows ищет исполняемый файл без пути сначала в папке программы
     * и в текущей папке — туда можно подложить свой powershell.exe, который получит пароли.
     */
    public static String systemExe(String relative) {
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) root = "C:\\Windows";
        return Path.of(root, relative).toString();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        in.transferTo(b);
        return b.toByteArray();
    }
}
