package co.edu.unipamplona.canchatrack.evaluations.application;

import java.net.InetAddress;
import java.util.UUID;

/** Quién actúa y desde dónde, para la auditoría; {@code role} es el rol con el que se audita la operación. */
public record EvaluationsActor(UUID id, String role, InetAddress ip, String userAgent) {

    public static final String COORDINATOR = "COORDINATOR";
}
