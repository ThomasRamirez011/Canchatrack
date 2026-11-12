package co.edu.unipamplona.canchatrack.scheduling.application;

import java.net.InetAddress;
import java.util.UUID;

/** Quién programa y desde dónde, para la auditoría. La autorización por rol se aplica en la capa web. */
public record SchedulingActor(UUID id, InetAddress ip, String userAgent) {

    public static final String ROLE = "COORDINATOR";
}
