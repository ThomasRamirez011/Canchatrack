package co.edu.unipamplona.canchatrack.identity.application;

import java.net.InetAddress;

/** Origen de la solicitud, solo para auditoría y trazabilidad de sesiones. Puede estar vacío. */
public record ClientOrigin(InetAddress ip, String userAgent) {

    public static final ClientOrigin UNKNOWN = new ClientOrigin(null, null);
}
