package co.edu.unipamplona.canchatrack.reports.infrastructure;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Generador mínimo de PDF 1.4 para informes de texto: A4, Helvetica y Helvetica-Bold (fuentes estándar, sin incrustar),
 * codificación WinAnsi (cubre el español), saltos de página automáticos y pie con numeración. Sin dependencias externas y
 * determinista: el mismo contenido produce los mismos bytes, que es lo que permite fijar una huella.
 *
 * <p>No mide el ancho real de los glifos: ajusta las líneas con un ancho medio conservador por carácter.
 */
final class SimplePdf {

    private static final Charset WIN_ANSI = Charset.forName("windows-1252");
    private static final DateTimeFormatter PDF_DATE = DateTimeFormatter.ofPattern("'D:'yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);

    static final float PAGE_WIDTH = 595.28f;
    static final float PAGE_HEIGHT = 841.89f;
    static final float MARGIN = 50f;
    private static final float FOOTER_Y = 30f;
    private static final float TOP = PAGE_HEIGHT - MARGIN;
    private static final float BOTTOM = MARGIN + 10f;
    /** Ancho medio de un carácter de Helvetica como fracción del tamaño; se toma por encima del real para no desbordar. */
    private static final float AVERAGE_GLYPH = 0.55f;

    private final String title;
    private final String footerLeft;
    private final Instant created;
    private final List<StringBuilder> pages = new ArrayList<>();
    private StringBuilder page;
    private float y;

    SimplePdf(String title, String footerLeft, Instant created) {
        this.title = title;
        this.footerLeft = footerLeft;
        this.created = created;
        newPage();
    }

    private void newPage() {
        page = new StringBuilder();
        pages.add(page);
        y = TOP;
    }

    private void ensureSpace(float needed) {
        if (y - needed < BOTTOM) {
            newPage();
        }
    }

    /** Cantidad de caracteres que caben en {@code width} puntos con ese tamaño de letra. */
    static int charsFor(float width, float size) {
        return Math.max(1, (int) (width / (size * AVERAGE_GLYPH)));
    }

    // ---------- bloques ----------

    void heading(String text, float size) {
        ensureSpace(size * 2.2f);
        y -= size * 0.6f;
        write(MARGIN, y - size, size, true, text);
        y -= size * 1.5f;
    }

    void space(float points) {
        y -= points;
        if (y < BOTTOM) {
            newPage();
        }
    }

    /** Párrafo con ajuste de línea. */
    void paragraph(String text, float size, boolean bold) {
        for (String line : wrap(text, charsFor(PAGE_WIDTH - 2 * MARGIN, size))) {
            ensureSpace(size * 1.4f);
            write(MARGIN, y - size, size, bold, line);
            y -= size * 1.4f;
        }
    }

    /** Par «etiqueta: valor» en una línea (la etiqueta en negrita). */
    void field(String label, String value, float size) {
        ensureSpace(size * 1.4f);
        String shown = label + ": ";
        write(MARGIN, y - size, size, true, shown);
        float x = MARGIN + shown.length() * size * 0.56f;
        int room = charsFor(PAGE_WIDTH - MARGIN - x, size);
        List<String> lines = wrap(value == null ? "—" : value, room);
        write(x, y - size, size, false, lines.get(0));
        y -= size * 1.4f;
        for (int i = 1; i < lines.size(); i++) {
            ensureSpace(size * 1.4f);
            write(x, y - size, size, false, lines.get(i));
            y -= size * 1.4f;
        }
    }

    /** Fila de tabla: cada celda en su columna (posiciones x relativas al margen), recortada si no cabe. */
    void row(float[] columnStarts, String[] cells, float size, boolean bold) {
        ensureSpace(size * 1.5f);
        for (int i = 0; i < cells.length; i++) {
            float end = i + 1 < columnStarts.length ? columnStarts[i + 1] - 6f : PAGE_WIDTH - 2 * MARGIN;
            String text = fit(cells[i] == null ? "" : cells[i], charsFor(end - columnStarts[i], size));
            write(MARGIN + columnStarts[i], y - size, size, bold, text);
        }
        y -= size * 1.5f;
    }

    // ---------- salida ----------

    byte[] build() {
        int total = pages.size();
        List<String> streams = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            StringBuilder content = new StringBuilder(pages.get(i));
            content.append("0.45 g\n");
            appendText(content, MARGIN, FOOTER_Y, 8f, false, footerLeft);
            String number = "Página " + (i + 1) + " de " + total;
            appendText(content, PAGE_WIDTH - MARGIN - number.length() * 8f * 0.5f, FOOTER_Y, 8f, false, number);
            content.append("0 g\n");
            streams.add(content.toString());
        }

        // Objetos: 1 catálogo, 2 páginas, 3 y 4 fuentes, 5 info; luego, por página, su objeto y su contenido.
        List<byte[]> objects = new ArrayList<>();
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < total; i++) {
            kids.append(6 + 2 * i).append(" 0 R ");
        }
        objects.add(ascii("<< /Type /Catalog /Pages 2 0 R >>"));
        objects.add(ascii("<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + total + " >>"));
        objects.add(ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
        objects.add(ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"));
        objects.add(bytes("<< /Title " + literal(title) + " /Producer (CanchaTrack) /CreationDate (" + PDF_DATE.format(created) + ") >>"));
        for (int i = 0; i < total; i++) {
            int contentObject = 7 + 2 * i;
            objects.add(ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + PAGE_WIDTH + " " + PAGE_HEIGHT
                    + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + contentObject + " 0 R >>"));
            byte[] data = streams.get(i).getBytes(WIN_ANSI);
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            stream.writeBytes(ascii("<< /Length " + data.length + " >>\nstream\n"));
            stream.writeBytes(data);
            stream.writeBytes(ascii("\nendstream"));
            objects.add(stream.toByteArray());
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii("%PDF-1.4\n%âãÏÓ\n"));
        int[] offsets = new int[objects.size()];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i] = out.size();
            out.writeBytes(ascii((i + 1) + " 0 obj\n"));
            out.writeBytes(objects.get(i));
            out.writeBytes(ascii("\nendobj\n"));
        }
        int xref = out.size();
        StringBuilder table = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) {
            table.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset));
        }
        table.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R /Info 5 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        out.writeBytes(ascii(table.toString()));
        return out.toByteArray();
    }

    // ---------- utilidades ----------

    private void write(float x, float baseline, float size, boolean bold, String text) {
        appendText(page, x, baseline, size, bold, text);
    }

    private static void appendText(StringBuilder target, float x, float baseline, float size, boolean bold, String text) {
        target.append("BT /").append(bold ? "F2" : "F1").append(' ').append(number(size)).append(" Tf ")
                .append(number(x)).append(' ').append(number(baseline)).append(" Td ")
                .append(literal(text)).append(" Tj ET\n");
    }

    private static String number(float value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    /** Cadena literal PDF: escapa \ ( ) y descarta caracteres de control; lo que WinAnsi no tiene sale como «?». */
    static String literal(String text) {
        StringBuilder out = new StringBuilder("(");
        String clean = (text == null ? "" : text).replaceAll("\\p{Cntrl}", " ");
        // Se pasa por WinAnsi para sustituir lo no representable antes de escribir el flujo.
        clean = new String(clean.getBytes(WIN_ANSI), WIN_ANSI);
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '\\' || c == '(' || c == ')') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.append(')').toString();
    }

    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        String clean = (text == null ? "" : text).replaceAll("\\p{Cntrl}", " ").trim();
        if (clean.isEmpty()) {
            lines.add("");
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (String word : clean.split("\\s+")) {
            String piece = word;
            while (piece.length() > width) { // palabra más larga que la línea: se parte
                if (line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                lines.add(piece.substring(0, width));
                piece = piece.substring(width);
            }
            if (line.length() > 0 && line.length() + 1 + piece.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(piece);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    static String fit(String text, int width) {
        String clean = text.replaceAll("\\p{Cntrl}", " ").trim();
        return clean.length() <= width ? clean : clean.substring(0, Math.max(0, width - 3)) + "...";
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(WIN_ANSI);
    }
}
