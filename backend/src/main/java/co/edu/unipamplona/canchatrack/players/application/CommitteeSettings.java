package co.edu.unipamplona.canchatrack.players.application;

import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Parámetros de la decisión del comité. Son provisionales hasta que el club responda DP-01: el ERS describe el panel
 * como entrenador titular, preparador físico y delegado del comité, pero el sistema solo distingue los roles COACH,
 * EVALUATOR y COMMITTEE; por eso los roles admitidos en el panel y la posibilidad de exceder el cupo son configurables.
 *
 * @param panelRoles              roles que habilitan a una cuenta para integrar el panel (nunca GUARDIAN)
 * @param allowCapacityException  si el comité puede asignar a una categoría llena dejando constancia del motivo
 * @param zone                    zona horaria del club para decidir si la fecha del acta ya ocurrió
 */
public record CommitteeSettings(Set<String> panelRoles, boolean allowCapacityException, ZoneId zone) {

    private static final Set<String> STAFF_ROLES = Set.of("COORDINATOR", "COACH", "EVALUATOR", "COMMITTEE");

    public CommitteeSettings {
        Set<String> normalized = new LinkedHashSet<>();
        if (panelRoles != null) {
            for (String role : panelRoles) {
                if (role != null && !role.isBlank()) {
                    normalized.add(role.trim().toUpperCase(java.util.Locale.ROOT));
                }
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("canchatrack.committee.panel-roles no puede quedar vacío");
        }
        if (!STAFF_ROLES.containsAll(normalized)) {
            throw new IllegalArgumentException("canchatrack.committee.panel-roles solo admite roles del personal: " + normalized);
        }
        panelRoles = Set.copyOf(normalized);
        if (zone == null) {
            throw new IllegalArgumentException("zone is required");
        }
    }

    public static CommitteeSettings of(Collection<String> panelRoles, boolean allowCapacityException, ZoneId zone) {
        return new CommitteeSettings(panelRoles == null ? Set.of() : new LinkedHashSet<>(panelRoles), allowCapacityException, zone);
    }
}
