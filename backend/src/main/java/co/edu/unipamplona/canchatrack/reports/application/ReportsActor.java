package co.edu.unipamplona.canchatrack.reports.application;

import java.net.InetAddress;
import java.util.UUID;

/** Quién consulta, con qué rol y desde dónde, para auditar las exportaciones. La autorización se aplica en la capa web. */
public record ReportsActor(UUID id, String role, InetAddress ip, String userAgent) {
}
