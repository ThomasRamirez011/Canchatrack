package co.edu.unipamplona.canchatrack.audit.application;

import java.net.InetAddress;
import java.util.UUID;

/** Quién consulta y desde dónde, para dejar rastro de la propia consulta. La autorización por rol se aplica en la capa web. */
public record AuditActor(UUID id, InetAddress ip, String userAgent) {

    public static final String ROLE = "COORDINATOR";
}
