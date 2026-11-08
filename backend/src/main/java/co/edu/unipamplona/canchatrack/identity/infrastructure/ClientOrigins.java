package co.edu.unipamplona.canchatrack.identity.infrastructure;

import java.net.InetAddress;
import java.net.UnknownHostException;

import co.edu.unipamplona.canchatrack.identity.application.ClientOrigin;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Origen de la solicitud para auditoría. Usa la dirección que ve el contenedor; detrás de un proxy inverso depende de
 * {@code server.forward-headers-strategy} (ya activo en producción). {@code getRemoteAddr()} devuelve un literal de
 * IP, por lo que {@code getByName} no hace consultas DNS.
 */
final class ClientOrigins {

    private ClientOrigins() {
    }

    static ClientOrigin from(HttpServletRequest request) {
        InetAddress ip = null;
        String remote = request.getRemoteAddr();
        if (remote != null && !remote.isBlank()) {
            try {
                ip = InetAddress.getByName(remote);
            } catch (UnknownHostException e) {
                ip = null;
            }
        }
        return new ClientOrigin(ip, request.getHeader("User-Agent"));
    }
}
