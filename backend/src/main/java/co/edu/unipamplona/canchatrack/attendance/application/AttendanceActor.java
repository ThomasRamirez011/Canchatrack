package co.edu.unipamplona.canchatrack.attendance.application;

import java.net.InetAddress;
import java.util.Set;
import java.util.UUID;

/** Quién registra y con qué roles. El coordinador gestiona cualquier sesión; el entrenador, las de sus eventos. */
public record AttendanceActor(UUID id, Set<String> roles, InetAddress ip, String userAgent) {

    public boolean isCoordinator() {
        return roles.contains("COORDINATOR");
    }

    /** Rol con el que se audita la acción. */
    public String auditRole() {
        return isCoordinator() ? "COORDINATOR" : "COACH";
    }
}
