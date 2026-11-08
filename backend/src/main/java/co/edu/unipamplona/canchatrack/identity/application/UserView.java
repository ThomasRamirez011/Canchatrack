package co.edu.unipamplona.canchatrack.identity.application;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.identity.domain.RoleCode;
import co.edu.unipamplona.canchatrack.identity.domain.UserAccount;

/** Datos de una cuenta aptos para exponer (nunca incluye el hash ni el estado de bloqueo). */
public record UserView(UUID id, String email, String fullName, List<String> roles) {

    public static UserView from(UserAccount account) {
        List<String> roles = account.roles().stream()
                .map(RoleCode::name)
                .sorted(Comparator.naturalOrder())
                .toList();
        return new UserView(account.id(), account.email(), account.fullName(), roles);
    }
}
