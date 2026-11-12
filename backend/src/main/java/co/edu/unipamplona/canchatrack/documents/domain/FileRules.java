package co.edu.unipamplona.canchatrack.documents.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reglas puras de los archivos que se suben (DDS 12: formato, tamaño e integridad). El tipo declarado por el cliente
 * no basta: se comprueba también la firma de los primeros bytes para no aceptar, por ejemplo, un ejecutable renombrado.
 * Los tipos admitidos son provisionales hasta que el club defina los documentos que exige (DP-04).
 */
public final class FileRules {

    public static final int MAX_NAME = 255;
    public static final Set<String> ALLOWED_TYPES = Set.of("application/pdf", "image/png", "image/jpeg");

    private FileRules() {
    }

    /** Quita parámetros ({@code ; charset=…}) y normaliza a minúsculas. */
    public static String normalizeContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String base = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return base.trim().toLowerCase(Locale.ROOT);
    }

    /** Solo el nombre de archivo, sin rutas de ningún sistema operativo ni caracteres de control; vacío si no queda nada. */
    public static String sanitizeName(String originalName) {
        if (originalName == null) {
            return "";
        }
        String name = originalName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").trim();
        return name.length() > MAX_NAME ? name.substring(name.length() - MAX_NAME) : name;
    }

    /** @return los motivos de rechazo; vacío si el archivo es admisible */
    public static List<String> violations(String originalName, String contentType, long size, long maxSize, byte[] head) {
        List<String> problems = new ArrayList<>();
        if (sanitizeName(originalName).isEmpty()) {
            problems.add("El archivo debe tener nombre.");
        }
        if (size <= 0) {
            problems.add("El archivo está vacío.");
        } else if (size > maxSize) {
            problems.add("El archivo supera el tamaño máximo de " + (maxSize / (1024 * 1024)) + " MB.");
        }
        String type = normalizeContentType(contentType);
        if (!ALLOWED_TYPES.contains(type)) {
            problems.add("Formato no admitido; use PDF, PNG o JPEG.");
        } else if (size > 0 && !signatureMatches(type, head)) {
            problems.add("El contenido del archivo no corresponde al formato declarado.");
        }
        return problems;
    }

    static boolean signatureMatches(String type, byte[] head) {
        if (head == null) {
            return false;
        }
        return switch (type) {
            case "application/pdf" -> startsWith(head, 0x25, 0x50, 0x44, 0x46);          // %PDF
            case "image/png" -> startsWith(head, 0x89, 0x50, 0x4E, 0x47);                // \x89PNG
            case "image/jpeg" -> startsWith(head, 0xFF, 0xD8, 0xFF);
            default -> false;
        };
    }

    private static boolean startsWith(byte[] head, int... signature) {
        if (head.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((head[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
