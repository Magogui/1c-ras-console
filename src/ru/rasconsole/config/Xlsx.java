package ru.rasconsole.config;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Минимальная запись книги Excel (.xlsx) без сторонних библиотек: листы с заголовком (жирный, закреплён,
 * с автофильтром), строки — текст, числа и даты. Этого достаточно для отчётов; формулы и стили не нужны.
 */
public final class Xlsx {

    /** Лист: имя, заголовки колонок, ширины (в символах) и строки. */
    public static final class Sheet {
        final String name;
        final List<String> header;
        final List<Integer> widths = new ArrayList<>();
        final List<List<Object>> rows = new ArrayList<>();

        public Sheet(String name, List<String> header) {
            this.name = name.length() > 31 ? name.substring(0, 31) : name;
            this.header = header;
            for (String h : header) widths.add(Math.max(10, Math.min(60, h.length() + 2)));
        }

        public Sheet row(Object... values) {
            List<Object> r = new ArrayList<>(java.util.Arrays.asList(values));
            for (int i = 0; i < r.size() && i < widths.size(); i++) {
                Object v = r.get(i);
                int len = v == null ? 0 : v instanceof Date ? 19 : v.toString().length();
                widths.set(i, Math.max(widths.get(i), Math.min(60, len + 2)));
            }
            rows.add(r);
            return this;
        }

        public List<String> header() {
            return header;
        }

        public List<List<Object>> rows() {
            return rows;
        }

        public String name() {
            return name;
        }
    }

    private Xlsx() {
    }

    public static void write(Path file, List<Sheet> sheets) throws IOException {
        try (OutputStream os = Files.newOutputStream(file); ZipOutputStream z = new ZipOutputStream(os)) {
            put(z, "[Content_Types].xml", contentTypes(sheets.size()));
            put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>");
            StringBuilder wb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
            StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < sheets.size(); i++) {
                int n = i + 1;
                wb.append("<sheet name=\"").append(esc(sheets.get(i).name)).append("\" sheetId=\"").append(n)
                    .append("\" r:id=\"rId").append(n).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(n)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(n).append(".xml\"/>");
                Sheet s = sheets.get(i);
                if (!s.rows.isEmpty()) {
                    names.append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"").append(i).append("\" hidden=\"1\">'")
                        .append(esc(s.name.replace("'", "''"))).append("'!$A$1:$").append(col(s.header.size() - 1)).append('$')
                        .append(s.rows.size() + 1).append("</definedName>");
                }
            }
            wb.append("</sheets>");
            if (names.length() > 0) wb.append("<definedNames>").append(names).append("</definedNames>");
            wb.append("</workbook>");
            rels.append("<Relationship Id=\"rId").append(sheets.size() + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
                .append("</Relationships>");
            put(z, "xl/workbook.xml", wb.toString());
            put(z, "xl/_rels/workbook.xml.rels", rels.toString());
            put(z, "xl/styles.xml", STYLES);
            for (int i = 0; i < sheets.size(); i++) put(z, "xl/worksheets/sheet" + (i + 1) + ".xml", sheet(sheets.get(i)));
        }
    }

    // стили: 0 — обычный, 1 — жирный заголовок с заливкой, 2 — дата
    private static final String STYLES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
        + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"dd.mm.yyyy hh:mm:ss\"/></numFmts>"
        + "<fonts count=\"2\"><font><sz val=\"10\"/><name val=\"Arial\"/></font><font><b/><sz val=\"10\"/><name val=\"Arial\"/></font></fonts>"
        + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
        + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDDE6F2\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
        + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
        + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
        + "<cellXfs count=\"3\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
        + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
        + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs>"
        + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
        + "</styleSheet>";

    private static String sheet(Sheet s) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        sb.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>"
            + "</sheetView></sheetViews>");
        sb.append("<cols>");
        for (int i = 0; i < s.widths.size(); i++) {
            sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"")
                .append(s.widths.get(i)).append("\" customWidth=\"1\"/>");
        }
        sb.append("</cols><sheetData>");
        sb.append("<row r=\"1\">");
        for (int c = 0; c < s.header.size(); c++) cell(sb, c, 1, s.header.get(c), 1);
        sb.append("</row>");
        for (int r = 0; r < s.rows.size(); r++) {
            sb.append("<row r=\"").append(r + 2).append("\">");
            List<Object> row = s.rows.get(r);
            for (int c = 0; c < row.size(); c++) cell(sb, c, r + 2, row.get(c), 0);
            sb.append("</row>");
        }
        sb.append("</sheetData>");
        if (!s.rows.isEmpty()) {
            sb.append("<autoFilter ref=\"A1:").append(col(s.header.size() - 1)).append(s.rows.size() + 1).append("\"/>");
        }
        sb.append("</worksheet>");
        return sb.toString();
    }

    private static void cell(StringBuilder sb, int c, int r, Object v, int style) {
        if (v == null) return;
        String ref = col(c) + r;
        if (v instanceof Boolean b) v = b ? "да" : "нет";
        if (v instanceof Number n && style == 0) {
            sb.append("<c r=\"").append(ref).append("\"><v>").append(n).append("</v></c>");
        } else if (v instanceof Date d && style == 0) {
            if (d.getTime() <= 0) return;
            sb.append("<c r=\"").append(ref).append("\" s=\"2\"><v>").append(excelDate(d)).append("</v></c>");
        } else {
            sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(style > 0 ? " s=\"" + style + "\"" : "")
                .append("><is><t xml:space=\"preserve\">").append(esc(v.toString())).append("</t></is></c>");
        }
    }

    /** Дата Excel: дни от 30.12.1899 в местном времени. */
    private static String excelDate(Date d) {
        long local = d.getTime() + java.util.TimeZone.getDefault().getOffset(d.getTime());
        double days = local / 86_400_000.0 + 25569.0;
        return String.format(java.util.Locale.ROOT, "%.8f", days);
    }

    static String col(int i) {
        StringBuilder sb = new StringBuilder();
        i++;
        while (i > 0) {
            int m = (i - 1) % 26;
            sb.insert(0, (char) ('A' + m));
            i = (i - 1) / 26;
        }
        return sb.toString();
    }

    private static String contentTypes(int n) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
            + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 1; i <= n; i++) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return sb.append("</Types>").toString();
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> {
                    // управляющие символы недопустимы в XML
                    if (ch < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') sb.append(' ');
                    else sb.append(ch);
                }
            }
        }
        return sb.toString();
    }

    /** Та же книга одной HTML-страницей (для почты и браузера). */
    public static void writeHtml(Path file, String title, List<Sheet> sheets) throws IOException {
        StringBuilder sb = new StringBuilder("<!DOCTYPE html>\n<html lang=\"ru\"><head><meta charset=\"utf-8\"><title>")
            .append(esc(title)).append("</title><style>"
                + "body{font-family:Segoe UI,Arial,sans-serif;font-size:13px;margin:20px;color:#222}"
                + "h1{font-size:20px}h2{font-size:16px;margin-top:28px}"
                + "table{border-collapse:collapse;margin-top:6px}th,td{border:1px solid #c8d0da;padding:3px 8px;text-align:left;vertical-align:top}"
                + "th{background:#dde6f2;position:sticky;top:0}tr:nth-child(even) td{background:#f6f8fb}"
                + "td.num{text-align:right}.meta{color:#666}nav a{margin-right:14px}"
                + "@media (prefers-color-scheme:dark){body{background:#1f2124;color:#e3e3e3}th{background:#2f3a4a}"
                + "td,th{border-color:#444}tr:nth-child(even) td{background:#26292d}.meta{color:#aaa}a{color:#8ab4f8}}"
                + "</style></head><body>");
        sb.append("<h1>").append(esc(title)).append("</h1><p class=\"meta\">Сформирован ")
            .append(new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date())).append("</p><nav>");
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<a href=\"#s").append(i).append("\">").append(esc(sheets.get(i).name)).append(" (")
                .append(sheets.get(i).rows.size()).append(")</a>");
        }
        sb.append("</nav>");
        SimpleDateFormat df = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");
        for (int i = 0; i < sheets.size(); i++) {
            Sheet s = sheets.get(i);
            sb.append("<h2 id=\"s").append(i).append("\">").append(esc(s.name)).append("</h2><table><thead><tr>");
            for (String h : s.header) sb.append("<th>").append(esc(h)).append("</th>");
            sb.append("</tr></thead><tbody>");
            for (List<Object> r : s.rows) {
                sb.append("<tr>");
                for (Object v : r) {
                    if (v instanceof Number) {
                        sb.append("<td class=\"num\">").append(v).append("</td>");
                    } else {
                        String t = v == null ? "" : v instanceof Date d ? (d.getTime() <= 0 ? "" : df.format(d))
                            : v instanceof Boolean b ? (b ? "да" : "нет") : v.toString();
                        sb.append("<td>").append(esc(t)).append("</td>");
                    }
                }
                sb.append("</tr>");
            }
            sb.append("</tbody></table>");
        }
        sb.append("</body></html>\n");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }
}
