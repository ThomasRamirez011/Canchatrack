package co.edu.unipamplona.canchatrack.identity.application;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Puerto de salida: roles vigentes de cuentas activas. */
public interface StaffRoles {

    Map<UUID, Set<String>> activeRolesOf(Collection<UUID> userIds);
}
