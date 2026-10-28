package ru.rasconsole.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import ru.rasconsole.config.CredentialStore;
import ru.rasconsole.ras.Credentials;

/**
 * Отправка оповещений наружу: Telegram, webhook (JSON с полем {@code text} — подходит для Slack, Mattermost,
 * Rocket.Chat и своих обработчиков) и электронная почта (SMTP: SSL, STARTTLS или без шифрования).
 * <p>
 * Токен бота и пароль SMTP хранятся в {@link CredentialStore} (зашифрованы), остальное — в settings.properties.
 * Отправка идёт в отдельном потоке и не задерживает опрос серверов.
 */
public final class Notifier {

    /** Ключи секретов в хранилище паролей. */
    public static final String TELEGRAM_SECRET = "notify|telegram";
    public static final String SMTP_SECRET = "notify|smtp";

    /** Настройки каналов. */
    public static final class Channels {
        public boolean telegram;
        public String telegramChat = "";
        public boolean webhook;
        public String webhookUrl = "";
        public boolean email;
        public String smtpHost = "";
        public int smtpPort = 465;
        /** ssl — сразу TLS (465), starttls — 587, none — без шифрования (25). */
        public String smtpSecurity = "ssl";
        public String smtpUser = "";
        public String mailFrom = "";
        public String mailTo = "";
        /** Токен и пароль, введённые, но не сохранённые (нет хранилища или проверка из диалога); null — из хранилища. */
        public volatile String telegramToken;
        public volatile String smtpPassword;

        public Channels copy() {
            Channels c = new Channels();
            c.telegram = telegram;
            c.telegramChat = telegramChat;
            c.webhook = webhook;
            c.webhookUrl = webhookUrl;
            c.email = email;
            c.smtpHost = smtpHost;
            c.smtpPort = smtpPort;
            c.smtpSecurity = smtpSecurity;
            c.smtpUser = smtpUser;
            c.mailFrom = mailFrom;
            c.mailTo = mailTo;
            c.telegramToken = telegramToken;
            c.smtpPassword = smtpPassword;
            return c;
        }

        public boolean any() {
            return telegram || webhook || email;
        }
    }

    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notifier");
        t.setDaemon(true);
        return t;
    });

    private Notifier() {
    }

    /** Отправить во все включённые каналы в фоне; ошибки — в stderr и в {@code onError}. */
    public static void sendAsync(Channels ch, String caption, String text, java.util.function.Consumer<String> onError) {
        if (!ch.any()) return;
        SENDER.execute(() -> {
            for (String err : send(ch, caption, text)) {
                System.err.println("Оповещение: " + err);
                if (onError != null) onError.accept(err);
            }
        });
    }

    /** Отправить во все включённые каналы (блокирующий вызов). Возвращает ошибки по каналам. */
    public static List<String> send(Channels ch, String caption, String text) {
        List<String> errors = new ArrayList<>();
        String full = caption + "\n" + text;
        if (ch.telegram) {
            try {
                telegram(ch, full);
            } catch (Exception e) {
                // текст исключения может содержать адрес запроса, а в нём — токен бота
                String token = ch.telegramToken != null && !ch.telegramToken.isBlank() ? ch.telegramToken : secret(TELEGRAM_SECRET);
                String m = message(e);
                if (!token.isBlank()) m = m.replace(token, "***");
                errors.add("Telegram: " + m);
            }
        }
        if (ch.webhook) {
            try {
                webhook(ch, caption, text);
            } catch (Exception e) {
                errors.add("Webhook: " + message(e));
            }
        }
        if (ch.email) {
            try {
                email(ch, caption, text);
            } catch (Exception e) {
                errors.add("Почта: " + message(e));
            }
        }
        return errors;
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String secret(String key) {
        CredentialStore cs = Fx.credentialStore();
        Credentials c = cs == null ? null : cs.secret(key);
        return c == null ? "" : c.password();
    }

    // ---- Telegram ----

    private static void telegram(Channels ch, String text) throws IOException {
        String token = ch.telegramToken != null && !ch.telegramToken.isBlank() ? ch.telegramToken : secret(TELEGRAM_SECRET);
        if (token.isBlank()) throw new IOException("не задан токен бота");
        if (ch.telegramChat.isBlank()) throw new IOException("не задан chat_id");
        String body = "{\"chat_id\":" + jsonChat(ch.telegramChat.trim()) + ",\"text\":" + json(text)
            + ",\"disable_web_page_preview\":true}";
        Response r = post(URI.create("https://api.telegram.org/bot" + token + "/sendMessage"), body);
        if (r.code() != 200) {
            // токен в текст ошибки не попадает: Telegram возвращает только описание
            throw new IOException("HTTP " + r.code() + " " + shorten(r.body()));
        }
    }

    /** chat_id — число (в том числе отрицательное для групп) или @имя канала. */
    private static String jsonChat(String chat) {
        return chat.matches("-?\\d+") ? chat : json(chat);
    }

    // ---- webhook ----

    private static void webhook(Channels ch, String caption, String text) throws IOException {
        if (ch.webhookUrl.isBlank()) throw new IOException("не задан адрес");
        URI uri = URI.create(ch.webhookUrl.trim());
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("адрес должен начинаться с http:// или https://");
        }
        String body = "{\"text\":" + json(caption + "\n" + text) + ",\"title\":" + json(caption) + ",\"message\":" + json(text)
            + ",\"source\":\"RasConsole\",\"host\":" + json(hostName()) + ",\"time\":" + json(ZonedDateTime.now().toString()) + "}";
        Response r = post(uri, body);
        if (r.code() / 100 != 2) throw new IOException("HTTP " + r.code() + " " + shorten(r.body()));
    }

    private record Response(int code, String body) {
    }

    /**
     * POST JSON через HttpURLConnection. java.net.http.HttpClient не используется: в Windows ему нужен
     * служебный сокет во временной папке, и при некоторых путях TEMP он не создаётся.
     */
    private static Response post(URI uri, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(data);
            }
            int code = c.getResponseCode();
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new Response(code, body);
        } finally {
            c.disconnect();
        }
    }

    // ---- почта (SMTP) ----

    private static void email(Channels ch, String caption, String text) throws IOException {
        if (ch.smtpHost.isBlank()) throw new IOException("не задан SMTP-сервер");
        List<String> to = new ArrayList<>();
        for (String s : ch.mailTo.split("[,;\\s]+")) if (!s.isBlank()) to.add(s.trim());
        if (to.isEmpty()) throw new IOException("не заданы получатели");
        String from = ch.mailFrom.isBlank() ? ch.smtpUser : ch.mailFrom.trim();
        if (from.isBlank()) throw new IOException("не задан отправитель");
        // перевод строки в адресе дописал бы в письмо или в команды SMTP свои строки
        for (String a : to) if (!mailbox(a)) throw new IOException("недопустимый адрес получателя: " + a);
        if (!mailbox(from)) throw new IOException("недопустимый адрес отправителя: " + from);
        Smtp smtp = new Smtp();
        try {
            smtp.open(ch.smtpHost.trim(), ch.smtpPort, ch.smtpSecurity);
            if (!ch.smtpUser.isBlank()) smtp.login(ch.smtpUser.trim(), ch.smtpPassword != null && !ch.smtpPassword.isEmpty() ? ch.smtpPassword : secret(SMTP_SECRET));
            smtp.cmd("MAIL FROM:<" + from + ">", 250);
            for (String r : to) smtp.cmd("RCPT TO:<" + r + ">", 250, 251);
            smtp.cmd("DATA", 354);
            StringBuilder m = new StringBuilder();
            m.append("From: ").append(encodeWord("Консоль RAS")).append(" <").append(from).append(">\r\n");
            m.append("To: ").append(String.join(", ", to)).append("\r\n");
            m.append("Subject: ").append(encodeWord(caption)).append("\r\n");
            m.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ROOT).format(ZonedDateTime.now())).append("\r\n");
            m.append("MIME-Version: 1.0\r\n");
            m.append("Content-Type: text/plain; charset=UTF-8\r\n");
            m.append("Content-Transfer-Encoding: base64\r\n\r\n");
            String b64 = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString((text + "\r\n").getBytes(StandardCharsets.UTF_8));
            m.append(b64).append("\r\n.");
            smtp.cmd(m.toString(), 250);
            smtp.cmd("QUIT", 221);
        } finally {
            smtp.close();
        }
    }

    private static boolean mailbox(String a) {
        return a.matches("[^\\s<>\"(),;:@]+@[^\\s<>\"(),;:@]+");
    }

    private static String encodeWord(String s) {
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    /** Минимальный SMTP-клиент: EHLO, STARTTLS, AUTH LOGIN/PLAIN. */
    private static final class Smtp {
        private Socket socket;
        private BufferedReader in;
        private OutputStream out;
        private String host;
        private String ehlo = "";

        void open(String host, int port, String security) throws IOException {
            this.host = host;
            Socket plain = new Socket();
            plain.connect(new InetSocketAddress(host, port), 10_000);
            plain.setSoTimeout(20_000);
            socket = "ssl".equals(security) ? tls(plain) : plain;
            streams();
            expect(220);
            ehlo = cmd("EHLO " + hostName(), 250);
            if ("starttls".equals(security)) {
                cmd("STARTTLS", 220);
                socket = tls(socket);
                streams();
                ehlo = cmd("EHLO " + hostName(), 250);
            }
        }

        private SSLSocket tls(Socket s) throws IOException {
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(s, host, s.getPort(), true);
            javax.net.ssl.SSLParameters p = ssl.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(p);
            ssl.startHandshake();
            return ssl;
        }

        private void streams() throws IOException {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = socket.getOutputStream();
        }

        void login(String user, String password) throws IOException {
            String caps = ehlo.toUpperCase(Locale.ROOT);
            if (caps.contains("AUTH") && caps.matches("(?s).*AUTH[ =][^\\n]*PLAIN.*")) {
                String token = Base64.getEncoder().encodeToString(("\0" + user + "\0" + password).getBytes(StandardCharsets.UTF_8));
                send("AUTH PLAIN " + token);
                expect(235);
                return;
            }
            cmd("AUTH LOGIN", 334);
            cmd(Base64.getEncoder().encodeToString(user.getBytes(StandardCharsets.UTF_8)), 334);
            send(Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)));
            expect(235);
        }

        String cmd(String line, int... ok) throws IOException {
            send(line);
            return expect(ok);
        }

        private void send(String line) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        /** Прочитать (многострочный) ответ и проверить код. */
        private String expect(int... ok) throws IOException {
            StringBuilder all = new StringBuilder();
            String line;
            int code;
            do {
                line = in.readLine();
                if (line == null) throw new IOException("сервер закрыл соединение");
                all.append(line).append('\n');
                code = line.length() >= 3 ? parse(line.substring(0, 3)) : -1;
            } while (line.length() > 3 && line.charAt(3) == '-');
            for (int c : ok) if (c == code) return all.toString();
            throw new IOException("SMTP: " + all.toString().trim());
        }

        private static int parse(String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        void close() {
            try {
                if (socket != null) socket.close();
            } catch (IOException ignored) {
                // соединение уже закрыто
            }
        }
    }

    // ---- общее ----

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "localhost";
        }
    }

    private static String shorten(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
