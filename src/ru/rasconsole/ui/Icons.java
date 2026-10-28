package ru.rasconsole.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;

/** Иконки дерева 16×16, нарисованные векторно (в духе стандартной консоли). */
public final class Icons {

    private Icons() {
    }

    public static final String SERVERS = "servers";
    public static final String CLUSTER = "cluster";
    public static final String ADMINS = "admins";
    public static final String AGENT_ADMINS = "agentAdmins";
    public static final String MANAGERS = "managers";
    public static final String SERVICES = "services";
    public static final String WORKING_SERVERS = "workingServers";
    public static final String COMPUTER = "computer";
    public static final String INFOBASES = "infobases";
    public static final String INFOBASE = "infobase";
    public static final String PROCESSES = "processes";
    public static final String PROCESS = "process";
    public static final String SESSIONS = "sessions";
    public static final String CONNECTIONS = "connections";
    public static final String LOCKS = "locks";
    public static final String PROFILES = "profiles";
    public static final String PROFILE = "profile";
    public static final String FOLDER = "folder";
    public static final String RULES = "rules";
    public static final String COUNTERS = "counters";
    public static final String LIMITS = "limits";
    public static final String LICENSES = "licenses";
    public static final String FAVORITES = "favorites";

    /** Иконка по ключу; null для неизвестного ключа. */
    public static Node get(String key) {
        if (key == null) return null;
        Node n = switch (key) {
            case SERVERS -> group(
                p("M2 1.5h12v6H2z", "#5b7aa6"), p("M2 8.5h12v6H2z", "#5b7aa6"),
                p("M3.5 3.5h6v1h-6z M3.5 10.5h6v1h-6z", "#dfe8f5"),
                p("M11.5 3.5h1.5v1.5h-1.5z M11.5 10.5h1.5v1.5h-1.5z", "#7fe07f"));
            case CLUSTER -> group(
                p("M5 1h6v5H5z", "#4a8f5c"), p("M1 10h6v5H1z M9 10h6v5H9z", "#4a8f5c"),
                p("M7.5 6h1v2h-1z M4 8h8v1H4z M3.5 8h1v2h-1z M11.5 8h1v2h-1z", "#555555"));
            case ADMINS -> person("#b07d2b");
            case AGENT_ADMINS -> group(person("#a0522d"),
                p("M10 10.5a2 2 0 1 1 1.9 2.6l-.4.4h-1v1h-1v1H8v-1.6z", "#e0b850"));
            case MANAGERS -> gear("#6b6b6b");
            case SERVICES -> p("M1.5 2.5h2.5v2.5H1.5z M6 2.5h8.5v2.5H6z M1.5 6.75h2.5v2.5H1.5z M6 6.75h8.5v2.5H6z "
                + "M1.5 11h2.5v2.5H1.5z M6 11h8.5v2.5H6z", "#6b6b6b");
            case WORKING_SERVERS -> group(
                p("M2 1h12v14H2z", "#5b7aa6"),
                p("M3.5 3h9v2h-9z M3.5 7h9v2h-9z M3.5 11h9v2h-9z", "#dfe8f5"),
                p("M11 3.5h1v1h-1z M11 7.5h1v1h-1z M11 11.5h1v1h-1z", "#4caf50"));
            case COMPUTER -> group(
                evenOdd("M1 2h14v9.5H1z M2.3 3.3h11.4v6.9H2.3z", "#5b7aa6"),
                p("M2.3 3.3h11.4v6.9H2.3z", "#cfe0f5"),
                p("M6.5 11.5h3v1.5h-3z M4 13h8v1.5H4z", "#5b7aa6"));
            case INFOBASES -> group(folderShape("#e0b850"), cylinder(6, 6, 0.55));
            case INFOBASE -> cylinder(0, 0, 1);
            case PROCESSES -> group(folderShape("#c9b6e4"), chip(5, 5, 0.65));
            case PROCESS -> chip(0, 0, 1);
            case SESSIONS -> p("M5 1.5a2.6 2.6 0 1 1 0 5.2a2.6 2.6 0 1 1 0-5.2z M0.8 14c0-3.3 1.9-5.4 4.2-5.4s4.2 2.1 4.2 5.4z "
                + "M11.2 2.8a2.1 2.1 0 1 1 0 4.2a2.1 2.1 0 1 1 0-4.2z M10.3 8.4c2.7 0 4.9 1.7 4.9 5h-4.6c0-2-.1-3.6-.3-5z", "#2e7db5");
            case CONNECTIONS -> p("M5 1h1.5v4H5z M9.5 1H11v4H9.5z M3.5 5h9v3a4.5 4.5 0 0 1-3.5 4.4V15h-2v-2.6A4.5 4.5 0 0 1 3.5 8z", "#3a8a7a");
            case LOCKS -> group(
                p("M4.8 7.5V5a3.2 3.2 0 0 1 6.4 0v2.5H9.6V5a1.6 1.6 0 0 0-3.2 0v2.5z", "#777777"),
                p("M3 7.5h10v7.5H3z", "#d4a017"),
                p("M7.3 10h1.4v2.5H7.3z", "#6b4f00"));
            case PROFILES -> group(folderShape("#9cc79c"), shield(5, 5, 0.65));
            case PROFILE -> shield(0, 0, 1);
            case FOLDER -> folderShape("#e0b850");
            case RULES -> group(
                p("M1.5 2.5h9v2h-9z M1.5 7h12v2h-12z M1.5 11.5h7v2h-7z", "#8a6d3b"),
                p("M11.5 10.5l3.5 2-3.5 2z", "#c0392b"));
            case COUNTERS -> p("M1.5 9h3v6h-3z M6.5 5h3v10h-3z M11.5 1.5h3V15h-3z", "#2e7db5");
            case LICENSES -> group(
                evenOdd("M5 2.5a3.5 3.5 0 1 1 0 7a3.5 3.5 0 1 1 0-7z M5 4.5a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3z", "#d4a017"),
                p("M8 5.3h7v1.6h-1.3v2h-1.6v-2h-1v1.5H9.5V6.9H8z", "#d4a017"));
            case LIMITS -> group(
                p("M5 1h6l4 4v6l-4 4H5l-4-4V5z", "#c0392b"),
                p("M3.5 7h9v2h-9z", "#ffffff"));
            case FAVORITES -> p("M8 1l2.1 4.5 4.9.6-3.6 3.4.9 4.9L8 12l-4.3 2.4.9-4.9L1 6.1l4.9-.6z", "#e0a800");
            default -> null;
        };
        return n == null ? null : sized(n);
    }

    /** Иконка сервера RAS с индикатором состояния соединения. */
    public static Node server(boolean connected, boolean error) {
        return server(connected, error, false);
    }

    /** @param checking идёт проверка связи — жёлтая точка */
    public static Node server(boolean connected, boolean error, boolean checking) {
        Color dot = connected ? Color.web("#3cb043") : error ? Color.web("#d93025")
            : checking ? Color.web("#f2a900") : Color.web("#9e9e9e");
        Circle c = new Circle(12, 12, 3.4, dot);
        c.setStroke(Color.WHITE);
        c.setStrokeWidth(1);
        return sized(group(
            p("M2 2h11v11H2z", "#5b7aa6"),
            p("M3.5 4h8v1.5h-8z M3.5 7.5h8v1.5h-8z", "#dfe8f5"),
            c));
    }

    // ---- примитивы ----

    private static Node person(String color) {
        return p("M8 1.2a3.2 3.2 0 1 1 0 6.4a3.2 3.2 0 1 1 0-6.4z M1.8 15c0-3.9 2.8-6 6.2-6s6.2 2.1 6.2 6z", color);
    }

    private static Node gear(String color) {
        return evenOdd("M7 1h2v2.1a5 5 0 0 1 1.8.8l1.5-1.5 1.4 1.4-1.5 1.5a5 5 0 0 1 .8 1.8H15v2h-2.1a5 5 0 0 1-.8 1.8l1.5 1.5-1.4 1.4-1.5-1.5"
            + "a5 5 0 0 1-1.8.8V15H7v-2.1a5 5 0 0 1-1.8-.8l-1.5 1.5-1.4-1.4 1.5-1.5a5 5 0 0 1-.8-1.8H1V7h2.1a5 5 0 0 1 .8-1.8L2.4 3.7"
            + "l1.4-1.4 1.5 1.5A5 5 0 0 1 7 3.1z M8 5.8a2.2 2.2 0 1 0 0 4.4a2.2 2.2 0 1 0 0-4.4z", color);
    }

    private static Node folderShape(String color) {
        return group(p("M1 3h5l1.5 1.5H15V14H1z", color), p("M1 6h14v8H1z", darker(color)));
    }

    private static Node cylinder(double dx, double dy, double scale) {
        Group g = group(
            p("M2 4c0-1.7 2.7-3 6-3s6 1.3 6 3v8c0 1.7-2.7 3-6 3s-6-1.3-6-3z", "#d9a520"),
            p("M2 4c0 1.7 2.7 3 6 3s6-1.3 6-3c0-1.7-2.7-3-6-3s-6 1.3-6 3z", "#f5d77a"),
            p("M2 8c0 1.7 2.7 3 6 3s6-1.3 6-3v.9c0 1.7-2.7 3-6 3s-6-1.3-6-3z", "#b8860b"));
        return place(g, dx, dy, scale);
    }

    private static Node chip(double dx, double dy, double scale) {
        Group g = group(
            p("M5 1h1v3H5z M7.5 1h1v3h-1z M10 1h1v3h-1z M5 12h1v3H5z M7.5 12h1v3h-1z M10 12h1v3h-1z "
                + "M1 5h3v1H1z M1 7.5h3v1H1z M1 10h3v1H1z M12 5h3v1h-3z M12 7.5h3v1h-3z M12 10h3v1h-3z", "#555555"),
            p("M3.5 3.5h9v9h-9z", "#7a5ba6"),
            p("M6 6h4v4H6z", "#b9a3dd"));
        return place(g, dx, dy, scale);
    }

    private static Node shield(double dx, double dy, double scale) {
        Group g = group(
            p("M8 1l6 2.5v4c0 3.8-2.6 6.6-6 7.5c-3.4-.9-6-3.7-6-7.5v-4z", "#3d7c3d"),
            p("M8 2.6l4.5 1.9v3c0 2.9-1.9 5.1-4.5 5.9z", "#6fb56f"));
        return place(g, dx, dy, scale);
    }

    private static Node place(Group g, double dx, double dy, double scale) {
        if (scale != 1) {
            // масштабирование вокруг левого верхнего угла с переносом в (dx, dy)
            g.getTransforms().add(new javafx.scene.transform.Translate(dx, dy));
            g.getTransforms().add(new javafx.scene.transform.Scale(scale, scale, 0, 0));
        }
        return g;
    }

    private static SVGPath p(String content, String color) {
        SVGPath s = new SVGPath();
        s.setContent(content);
        s.setFill(Color.web(color));
        return s;
    }

    private static SVGPath evenOdd(String content, String color) {
        SVGPath s = p(content, color);
        s.setFillRule(FillRule.EVEN_ODD);
        return s;
    }

    private static Group group(Node... nodes) {
        return new Group(nodes);
    }

    private static String darker(String color) {
        Color c = Color.web(color).deriveColor(0, 1, 0.88, 1);
        return String.format("#%02x%02x%02x", (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255));
    }

    /** Фиксированный размер 16×16, чтобы текст в дереве не прыгал. */
    private static Node sized(Node n) {
        Rectangle frame = new Rectangle(0, 0, 16, 16);
        frame.setFill(Color.TRANSPARENT);
        return new Group(frame, n);
    }
}
