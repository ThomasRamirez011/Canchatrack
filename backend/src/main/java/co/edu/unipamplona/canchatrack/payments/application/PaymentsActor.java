package co.edu.unipamplona.canchatrack.payments.application;

import java.net.InetAddress;
import java.util.UUID;

/** Quién registra y desde dónde, para la auditoría. La autorización por rol se aplica en la capa web. */
public record PaymentsActor(UUID id, InetAddress ip, String userAgent) {

    public static final String ROLE = "COORDINATOR";
}
