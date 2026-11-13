package co.edu.unipamplona.canchatrack.identity.application;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Huella del sujeto que se limita. Nunca se guarda la IP ni el correo en claro. Una IPv6 se agrupa por su prefijo /64
 * (lo que recibe un cliente doméstico u oficina): si no, quien tenga un bloque IPv6 rotaría de dirección sin límite.
 */
final class RateLimitSubjects {

    private RateLimitSubjects() {
    }

    static String ipKey(InetAddress ip) {
        byte[] bytes = ip.getAddress();
        if (ip instanceof Inet6Address && bytes.length == 16) {
            return "v6:" + HexFormat.of().formatHex(bytes, 0, 8);
        }
        return "v4:" + ip.getHostAddress();
    }

    static String emailKey(String normalizedEmail) {
        return "email:" + normalizedEmail.trim().toLowerCase(Locale.ROOT);
    }

    static String hash(String key) {
        return OpaqueToken.sha256Hex(key);
    }
}
