package co.edu.unipamplona.canchatrack.players.application;

import java.net.InetAddress;
import java.util.UUID;

/** Quién administra y desde dónde, para la auditoría. La autorización por rol se aplica en la capa web. */
public record AdminActor(UUID id, InetAddress ip, String userAgent) {

    public static final String ROLE = "COORDINATOR";
}
