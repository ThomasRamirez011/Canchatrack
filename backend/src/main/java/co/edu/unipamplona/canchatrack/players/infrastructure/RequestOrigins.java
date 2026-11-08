package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.net.InetAddress;
import java.net.UnknownHostException;

import co.edu.unipamplona.canchatrack.players.application.AdminActor;
import co.edu.unipamplona.canchatrack.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;

/** Origen técnico de la solicitud, para auditoría. */
final class RequestOrigins {

    private RequestOrigins() {
    }

    /** {@code getRemoteAddr()} devuelve un literal de IP: {@code getByName} no hace consultas DNS. */
    static InetAddress remoteAddress(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) {
            return null;
        }
        try {
            return InetAddress.getByName(remote);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    static AdminActor adminActor(HttpServletRequest request) {
        return new AdminActor(CurrentUser.require().id(), remoteAddress(request), request.getHeader("User-Agent"));
    }
}
