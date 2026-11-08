package co.edu.unipamplona.canchatrack.identity.application;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import co.edu.unipamplona.canchatrack.identity.api.StaffDirectory;

@Service
public class StaffDirectoryService implements StaffDirectory {

    private final StaffRoles roles;

    public StaffDirectoryService(StaffRoles roles) {
        this.roles = roles;
    }

    @Override
    public Map<UUID, Set<String>> activeRolesOf(Collection<UUID> userIds) {
        Set<UUID> distinct = new LinkedHashSet<>();
        if (userIds != null) {
            userIds.stream().filter(Objects::nonNull).forEach(distinct::add);
        }
        return distinct.isEmpty() ? Map.of() : roles.activeRolesOf(distinct);
    }
}
