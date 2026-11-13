package co.edu.unipamplona.canchatrack.demo;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Carga datos de ejemplo (tres categorías, jugadores, cobros, asistencia y partidos) para poder ver el front funcionando.
 * Escribe con SQL directo porque recorrer el flujo real (convocatoria, radicación de un acudiente, acta del comité con
 * panel de evaluadores) sería mucho más largo que el valor de una demostración. Solo corre si
 * {@code canchatrack.demo.enabled=true} (lo activa el perfil {@code dev}), solo si todavía no hay categorías y solo si ya
 * existe un coordinador (el de arranque), de modo que repetirlo no duplica nada.
 */
@Component
@ConditionalOnProperty(name = "canchatrack.demo.enabled", havingValue = "true")
class DemoDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final BigDecimal MONTHLY = new BigDecimal("140000.00");
    private static final BigDecimal ENROLLMENT = new BigDecimal("80000.00");

    /** {nombre, apellidos} por categoría, en el orden Sub-15, Sub-13, Escuela de Fútbol. */
    private static final String[][][] PLAYERS = {
            {{"Santiago", "Rojas Vélez"}, {"Mateo", "Gómez Cárdenas"}, {"Daniel", "Herrera Mejía"}, {"Samuel", "Ortiz Pineda"},
                    {"Emmanuel", "Castro Salazar"}, {"Juan José", "Londoño Arias"}, {"Tomás", "Restrepo Ruiz"},
                    {"Nicolás", "Zapata Duque"}},
            {{"Sebastián", "Mora Duque"}, {"Alejandro", "Cuartas Gil"}, {"Felipe", "Osorio Ramírez"},
                    {"Miguel Ángel", "Bedoya Soto"}, {"Thiago", "Montoya Correa"}, {"Gabriel", "Escobar Lopera"},
                    {"Isaac", "Giraldo Henao"}},
            {{"Lucas", "Palacio Vargas"}, {"Martín", "Cano Torres"}, {"Benjamín", "Aristizábal Mesa"},
                    {"Dylan", "Echeverri Soto"}, {"Joaquín", "Marín Agudelo"}, {"Simón", "Uribe Patiño"}}
    };
    private static final String[] CATEGORY_NAMES = {"Sub-15", "Sub-13", "Escuela de Fútbol"};
    private static final int[] CAPACITY = {25, 25, 30};
    private static final String[] COACHES = {"Carlos Ramírez", "Laura Gómez", "Andrés Pérez"};
    private static final String[] COACH_EMAILS = {"carlos.ramirez@canchatrack.local", "laura.gomez@canchatrack.local",
            "andres.perez@canchatrack.local"};
    private static final String[] OPPONENTS = {"Atlético Sur", "Academia Cafetera", "Escuela Los Andes"};
    private static final int[] TRAINING_HOUR = {17, 15, 13};

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ZoneId zone;
    private final String staffPassword;

    DemoDataSeeder(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
                   @Value("${canchatrack.zone-id:America/Bogota}") String zoneId,
                   @Value("${canchatrack.demo.staff-password:Dev-Only-Change-Me-1}") String staffPassword) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.zone = ZoneId.of(zoneId);
        this.staffPassword = staffPassword;
    }

    /** Después de que arranque todo (incluida la cuenta inicial del coordinador). */
    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        seed();
    }

    void seed() {
        UUID coordinator = firstId("""
                SELECT u.id FROM users u JOIN user_roles ur ON ur.user_id = u.id JOIN roles r ON r.id = ur.role_id
                WHERE r.code = 'COORDINATOR' ORDER BY u.created_at, u.id LIMIT 1""");
        if (coordinator == null) {
            log.warn("Datos de demostración omitidos: todavía no existe un coordinador (defina la cuenta inicial).");
            return;
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM categories)", Boolean.class))) {
            log.info("Datos de demostración omitidos: la base ya tiene categorías.");
            return;
        }
        tx.executeWithoutResult(status -> load(coordinator));
        log.info("Datos de demostración cargados: 3 categorías, {} jugadores. Coordinador de prueba: ver application-dev.yml.",
                jdbc.queryForObject("SELECT count(*) FROM players", Integer.class));
    }

    private void load(UUID coordinator) {
        Instant now = clock.instant();
        ZonedDateTime local = now.atZone(zone);
        LocalDate today = local.toLocalDate();
        int year = today.getYear();

        UUID coachRole = firstId("SELECT id FROM roles WHERE code = 'COACH'");
        String hash = new BCryptPasswordEncoder(10).encode(staffPassword);
        UUID[] coach = new UUID[3];
        for (int c = 0; c < 3; c++) {
            coach[c] = ensureUser(COACH_EMAILS[c], COACHES[c], hash, coordinator);
            jdbc.update("INSERT INTO user_roles (user_id, role_id, created_by) VALUES (?, ?, ?)", coach[c], coachRole, coordinator);
        }

        UUID season = jdbc.queryForObject("""
                INSERT INTO seasons (name, start_at, end_at, status, created_by)
                VALUES (?, ?, ?, 'ACTIVE', ?) RETURNING id""", UUID.class, "Temporada " + year,
                OffsetDateTime.of(year, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(year, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC), coordinator);
        UUID monthlyFee = fee(season, "MONTHLY", "Mensualidad", MONTHLY, year, coordinator);
        fee(season, "ENROLLMENT", "Matrícula", ENROLLMENT, year, coordinator);

        UUID[] venue = new UUID[2];
        venue[0] = jdbc.queryForObject("INSERT INTO venues (name, address, created_by) VALUES (?, ?, ?) RETURNING id",
                UUID.class, "Cancha La Arboleda", "Calle 80 # 52-10", coordinator);
        venue[1] = jdbc.queryForObject("INSERT INTO venues (name, address, created_by) VALUES (?, ?, ?) RETURNING id",
                UUID.class, "Coliseo Norte", "Carrera 45 # 95-30", coordinator);

        // Rangos de nacimiento contiguos y sin cruces: Sub-15, Sub-13 y Escuela de Fútbol.
        int[][] birth = {{year - 15, year - 14}, {year - 13, year - 12}, {year - 11, year - 8}};
        UUID[] category = new UUID[3];
        for (int c = 0; c < 3; c++) {
            category[c] = jdbc.queryForObject("""
                    INSERT INTO categories (name, birth_year_min, birth_year_max, capacity, created_by)
                    VALUES (?, ?, ?, ?, ?) RETURNING id""", UUID.class, CATEGORY_NAMES[c], birth[c][0], birth[c][1],
                    CAPACITY[c], coordinator);
        }
        jdbc.update("UPDATE categories SET next_category_id = ? WHERE id = ?", category[0], category[1]);
        jdbc.update("UPDATE categories SET next_category_id = ? WHERE id = ?", category[1], category[2]);

        UUID minuteFile = jdbc.queryForObject("""
                INSERT INTO stored_files (original_name, content_type, size_bytes, storage_key, sha256, uploaded_by)
                VALUES ('acta-demo.pdf', 'application/pdf', 1, 'demo/acta-demo.pdf', ?, ?) RETURNING id""", UUID.class,
                "0".repeat(64), coordinator);

        int playerNumber = 0;
        for (int c = 0; c < 3; c++) {
            List<UUID> roster = new ArrayList<>();
            List<Boolean> paidCurrent = new ArrayList<>();
            String[][] names = PLAYERS[c];
            for (int i = 0; i < names.length; i++, playerNumber++) {
                UUID player = jdbc.queryForObject("""
                        INSERT INTO players (first_name, last_name, document_type, document_number, birth_date, status, created_by)
                        VALUES (?, ?, 'TI', ?, ?, 'ACTIVE', ?) RETURNING id""", UUID.class, names[i][0], names[i][1],
                        "DEMO" + (10000 + playerNumber),
                        LocalDate.of(birth[c][0] + (i % (birth[c][1] - birth[c][0] + 1)), 1 + (i * 3) % 12, 1 + (i * 7) % 27),
                        coordinator);
                UUID minute = jdbc.queryForObject("""
                        INSERT INTO committee_minutes (minute_number, meeting_date, player_id, decision, file_id, created_by)
                        VALUES ('ACTA-DEMO-1', ?, ?, 'ASSIGNED', ?, ?) RETURNING id""", UUID.class, today.minusDays(120),
                        player, minuteFile, coordinator);
                jdbc.update("""
                        INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind, minute_id, valid_from, created_by)
                        VALUES (?, ?, ?, 'COMMITTEE', ?, ?, ?)""", player, category[c], coach[c], minute,
                        OffsetDateTime.ofInstant(now.minusSeconds(90 * 86_400L), ZoneOffset.UTC), coordinator);
                roster.add(player);
                paidCurrent.add(billing(player, monthlyFee, i, today, now, coordinator));
            }
            events(c, category[c], coach[c], venue[c % 2], roster, paidCurrent, today, coordinator);
        }
    }

    /**
     * Mensualidad del mes anterior y del actual. Casi todos pagan al día; uno de cada cuatro deja pendiente el mes actual
     * (y uno de cada siete, también el anterior), para que el front muestre habilitados y no habilitados. Devuelve si el
     * jugador quedó al día.
     */
    private boolean billing(UUID player, UUID fee, int index, LocalDate today, Instant now, UUID coordinator) {
        LocalDate currentPeriod = today.withDayOfMonth(1);
        LocalDate previousPeriod = currentPeriod.minusMonths(1);
        boolean previousPaid = index % 7 != 5;
        boolean currentPaid = index % 4 != 3 && previousPaid;
        boolean partial = !currentPaid && index % 4 == 3 && index % 7 == 3;

        UUID previous = charge(player, fee, previousPeriod, coordinator);
        UUID current = charge(player, fee, currentPeriod, coordinator);
        if (previousPaid) {
            payment(player, fee, previous, MONTHLY, previousPeriod.plusDays(4 + index % 3), now, index, coordinator);
        }
        if (currentPaid) {
            payment(player, fee, current, MONTHLY, currentPeriod.plusDays(4 + index % 3), now, index, coordinator);
        } else if (partial) {
            payment(player, fee, current, MONTHLY.divide(BigDecimal.valueOf(2)), currentPeriod.plusDays(6), now, index, coordinator);
        }
        return previousPaid && currentPaid;
    }

    private UUID charge(UUID player, UUID fee, LocalDate period, UUID coordinator) {
        return jdbc.queryForObject("""
                INSERT INTO charges (player_id, fee_id, period, amount, due_date, created_by)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, player, fee, period, MONTHLY, period.plusDays(4), coordinator);
    }

    private void payment(UUID player, UUID fee, UUID charge, BigDecimal amount, LocalDate day, Instant now, int index,
                         UUID coordinator) {
        Instant paidAt = day.atTime(10, 0).atZone(zone).toInstant();
        if (paidAt.isAfter(now)) {
            paidAt = now.minusSeconds(3_600);
        }
        jdbc.update("""
                INSERT INTO payments (player_id, fee_id, charge_id, amount, method, reference, paid_at, recorded_by)
                VALUES (?, ?, ?, ?, 'TRANSFER', ?, ?, ?)""", player, fee, charge, amount, "TRX-" + (8400 + index * 13 + day.getDayOfMonth()),
                OffsetDateTime.ofInstant(paidAt, ZoneOffset.UTC), coordinator);
    }

    /** Entrenamientos pasados con asistencia cerrada, entrenamientos próximos, un partido jugado y otro por jugar. */
    private void events(int c, UUID category, UUID coach, UUID venue, List<UUID> roster, List<Boolean> upToDate,
                        LocalDate today, UUID coordinator) {
        int hour = TRAINING_HOUR[c];
        int[] pastDays = {-11, -9, -6, -4, -2};
        for (int s = 0; s < pastDays.length; s++) {
            UUID event = event("TRAINING", category, venue, today.plusDays(pastDays[s]), hour, 2, "CONFIRMED", coach, coordinator);
            jdbc.update("INSERT INTO attendance_sessions (event_id) VALUES (?)", event);
            for (int p = 0; p < roster.size(); p++) {
                int roll = (p * 7 + s * 3 + c) % 10;
                String status = roll < 7 ? "PRESENT" : roll == 7 ? "LATE" : roll == 8 ? "EXCUSED" : "ABSENT";
                jdbc.update("""
                        INSERT INTO attendance_records (event_id, player_id, status, excuse_accepted, client_uuid, recorded_by)
                        VALUES (?, ?, ?, ?, ?, ?)""", event, roster.get(p), status, "EXCUSED".equals(status) && s % 2 == 0,
                        UUID.randomUUID(), coach);
            }
            jdbc.update("UPDATE attendance_sessions SET closed_at = now(), closed_by = ? WHERE event_id = ?", coordinator, event);
        }
        for (int day : new int[]{1, 3, 5, 8, 10}) {
            event("TRAINING", category, venue, today.plusDays(day + c), hour, 2, "CONFIRMED", coach, coordinator);
        }

        UUID played = event("FRIENDLY_MATCH", category, venue, today.minusDays(7), hour - 1, 2, "COMPLETED", coach, coordinator);
        jdbc.update("""
                INSERT INTO matches (event_id, opponent, score_for, score_against, result_recorded_by, result_recorded_at, created_by)
                VALUES (?, ?, ?, ?, ?, now(), ?)""", played, OPPONENTS[(c + 1) % 3], 2 + c % 2, 1, coordinator, coordinator);

        UUID upcoming = event("FRIENDLY_MATCH", category, venue, today.plusDays(3 + c), hour - 1, 2, "CONFIRMED", coach, coordinator);
        UUID match = jdbc.queryForObject("INSERT INTO matches (event_id, opponent, created_by) VALUES (?, ?, ?) RETURNING id",
                UUID.class, upcoming, OPPONENTS[c], coordinator);
        for (int p = 0; p < roster.size(); p++) {
            if (upToDate.get(p)) {
                jdbc.update("""
                        INSERT INTO callup_entries (match_id, player_id, status, proposed_by, confirmed_by, confirmed_at)
                        VALUES (?, ?, 'CONFIRMED', ?, ?, now())""", match, roster.get(p), coach, coordinator);
            }
        }
    }

    private UUID event(String type, UUID category, UUID venue, LocalDate day, int hour, int hours, String status, UUID coach,
                       UUID coordinator) {
        ZonedDateTime start = day.atTime(hour, 0).atZone(zone);
        UUID id = jdbc.queryForObject("""
                INSERT INTO sports_events (event_type, category_id, venue_id, start_at, end_at, meeting_point, status, created_by)
                VALUES (?, ?, ?, ?, ?, 'Entrada principal', ?, ?) RETURNING id""", UUID.class, type, category, venue,
                OffsetDateTime.ofInstant(start.toInstant(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(start.plusHours(hours).toInstant(), ZoneOffset.UTC), status, coordinator);
        jdbc.update("INSERT INTO event_participants (event_id, user_id, role, created_by) VALUES (?, ?, 'HEAD_COACH', ?)",
                id, coach, coordinator);
        return id;
    }

    private UUID fee(UUID season, String concept, String name, BigDecimal amount, int year, UUID coordinator) {
        return jdbc.queryForObject("""
                INSERT INTO fees (season_id, concept, name, amount, valid_from, created_by)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, season, concept, name, amount,
                LocalDate.of(year, 1, 1), coordinator);
    }

    private UUID ensureUser(String email, String name, String hash, UUID coordinator) {
        UUID existing = firstId("SELECT id FROM users WHERE lower(email) = lower(?)", email);
        if (existing != null) {
            return existing;
        }
        return jdbc.queryForObject("""
                INSERT INTO users (email, full_name, password_hash, email_verified_at, created_by)
                VALUES (?, ?, ?, now(), ?) RETURNING id""", UUID.class, email, name, hash, coordinator);
    }

    private UUID firstId(String sql, Object... args) {
        List<UUID> ids = jdbc.query(sql, (rs, n) -> rs.getObject(1, UUID.class), args);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
