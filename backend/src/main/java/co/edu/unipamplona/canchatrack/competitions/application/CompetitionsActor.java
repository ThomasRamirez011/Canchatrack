package co.edu.unipamplona.canchatrack.competitions.application;

import java.net.InetAddress;
import java.util.Set;
import java.util.UUID;

/** Quién actúa, con qué roles y desde dónde, para autorizar por recurso y auditar. */
public record CompetitionsActor(UUID id, Set<String> roles, InetAddress ip, String userAgent) {

    public boolean isCoordinator() {
        return roles.contains("COORDINATOR");
    }

    public boolean seesAllMatches() {
        return roles.contains("COORDINATOR") || roles.contains("COMMITTEE");
    }

    /** Rol con el que queda registrada la acción en la auditoría. */
    public String auditRole() {
        if (roles.contains("COORDINATOR")) {
            return "COORDINATOR";
        }
        if (roles.contains("COACH")) {
            return "COACH";
        }
        if (roles.contains("GUARDIAN")) {
            return "GUARDIAN";
        }
        return roles.contains("COMMITTEE") ? "COMMITTEE" : "USER";
    }
}
