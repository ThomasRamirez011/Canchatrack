package co.edu.unipamplona.canchatrack.documents.application;

import java.net.InetAddress;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Quién actúa, con qué roles y desde dónde, para autorizar y auditar. */
public record DocumentActor(UUID id, Set<String> roles, InetAddress ip, String userAgent) {

    public static final String COORDINATOR = "COORDINATOR";
    public static final String COMMITTEE = "COMMITTEE";
    public static final String COACH = "COACH";
    public static final String GUARDIAN = "GUARDIAN";

    public DocumentActor {
        roles = Set.copyOf(roles);
    }

    public boolean has(String role) {
        return roles.contains(role);
    }

    /** Rol con el que se registra en la auditoría: el de mayor responsabilidad que tenga. */
    public String auditRole() {
        return List.of(COORDINATOR, COMMITTEE, COACH, GUARDIAN).stream().filter(roles::contains).findFirst()
                .orElse(roles.stream().sorted().findFirst().orElse("UNKNOWN"));
    }
}
