package co.edu.unipamplona.canchatrack.identity.infrastructure;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.identity.application.StaffRoles;

@Repository
public class JdbcStaffRoles implements StaffRoles {

    private final JdbcTemplate jdbc;

    public JdbcStaffRoles(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, Set<String>> activeRolesOf(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(userIds.size(), "?"));
        Map<UUID, Set<String>> result = new HashMap<>();
        jdbc.query("""
                SELECT ur.user_id, r.code
                FROM user_roles ur
                JOIN roles r ON r.id = ur.role_id
                JOIN users u ON u.id = ur.user_id
                WHERE ur.user_id IN (%s)
                  AND u.status = 'ACTIVE'
                  AND ur.valid_from <= now() AND (ur.valid_to IS NULL OR ur.valid_to > now())
                """.formatted(placeholders),
                rs -> {
                    result.computeIfAbsent(rs.getObject("user_id", UUID.class), id -> new HashSet<>())
                            .add(rs.getString("code"));
                },
                userIds.toArray());
        return result;
    }
}
