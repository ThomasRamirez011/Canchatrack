package co.edu.unipamplona.canchatrack.payments.infrastructure;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.payments.api.AccountStanding;

@Repository
public class JdbcAccountStanding implements AccountStanding {

    private final JdbcTemplate jdbc;
    private final int graceDays;

    public JdbcAccountStanding(JdbcTemplate jdbc, @Value("${canchatrack.payments.arrears-grace-days:0}") int graceDays) {
        this.jdbc = jdbc;
        this.graceDays = Math.max(0, graceDays);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> inArrears(Collection<UUID> playerIds, LocalDate asOf) {
        if (playerIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(playerIds.size(), "?"));
        Object[] args = new Object[playerIds.size() + 1];
        args[0] = asOf.minusDays(graceDays);
        int i = 1;
        for (UUID id : playerIds) {
            args[i++] = id;
        }
        return new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT player_id
                FROM charge_balances
                WHERE status IN ('PENDING', 'PARTIAL')
                  AND outstanding > 0
                  AND due_date < ?
                  AND player_id IN (%s)
                """.formatted(placeholders), UUID.class, args));
    }
}
