package co.edu.unipamplona.canchatrack.shared.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reglas de la lista blanca de orígenes CORS. Función pura: devuelve todos los problemas juntos para que el arranque
 * falle una sola vez con un mensaje completo.
 *
 * <p>Un origen válido es {@code esquema://host[:puerto]} sin ruta, consulta ni credenciales. Se exige HTTPS salvo en
 * {@code localhost}, {@code 127.0.0.1} y {@code [::1]} (desarrollo). El comodín {@code *} nunca se admite: la API usa
 * un token en la cabecera {@code Authorization} y abrirla a cualquier origen anularía la política del navegador.
 */
final class CorsOrigins {

    private CorsOrigins() {
    }

    /** Problemas de la lista; vacía si todo es válido. */
    static List<String> violations(List<String> origins) {
        List<String> problems = new ArrayList<>();
        if (origins == null) {
            return problems;
        }
        for (String origin : origins) {
            String problem = violation(origin);
            if (problem != null) {
                problems.add(problem);
            }
        }
        return problems;
    }

    private static String violation(String origin) {
        if (origin == null || origin.isBlank()) {
            return "hay un origen vacío";
        }
        String value = origin.trim();
        if (value.contains("*")) {
            return "'" + value + "': no se admiten comodines";
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return "'" + value + "': no es una URL válida";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return "'" + value + "': el esquema debe ser http o https";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "'" + value + "': falta el host";
        }
        if (uri.getUserInfo() != null) {
            return "'" + value + "': no puede llevar credenciales";
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return "'" + value + "': no puede llevar consulta ni fragmento";
        }
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty()) {
            return "'" + value + "': un origen no lleva ruta ni barra final";
        }
        if (scheme.equals("http") && !isLoopback(host)) {
            return "'" + value + "': fuera de localhost el origen debe ser https";
        }
        return null;
    }

    private static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]");
    }
}
