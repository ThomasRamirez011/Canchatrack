package co.edu.unipamplona.canchatrack.identity.api;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Fachada pública: roles vigentes de cuentas del personal. Los demás módulos no consultan {@code users} ni
 * {@code user_roles} por su cuenta; la vigencia (cuenta activa y rol dentro de su ventana) se evalúa con el reloj de la
 * base de datos.
 */
public interface StaffDirectory {

    /**
     * @return por cada cuenta <b>activa</b> con al menos un rol vigente, sus códigos de rol; las cuentas inexistentes,
     *         deshabilitadas o sin roles vigentes no aparecen en el mapa
     */
    Map<UUID, Set<String>> activeRolesOf(Collection<UUID> userIds);
}
