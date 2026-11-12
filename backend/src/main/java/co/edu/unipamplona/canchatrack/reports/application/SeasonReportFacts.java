package co.edu.unipamplona.canchatrack.reports.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Datos deportivos de un jugador en una temporada, tal como los lee el informe (RF-049): asistencia, evaluaciones
 * publicadas (rondas cerradas) y participación en partidos. No contiene documento de identidad, datos de salud ni
 * justificaciones de los evaluadores.
 */
public record SeasonReportFacts(Season season, Player player, Period period, Attendance attendance,
                                List<Evaluation> evaluations, List<MatchLine> matches, Participation participation) {

    public SeasonReportFacts {
        evaluations = List.copyOf(evaluations);
        matches = List.copyOf(matches);
    }

    public static SeasonReportFacts of(Season season, Player player, Period period, Attendance attendance,
                                       List<Evaluation> evaluations, List<MatchLine> matches) {
        int minutes = 0;
        int goals = 0;
        for (MatchLine match : matches) {
            minutes += match.minutes() == null ? 0 : match.minutes();
            goals += match.goals() == null ? 0 : match.goals();
        }
        return new SeasonReportFacts(season, player, period, attendance, evaluations, matches,
                new Participation(matches.size(), minutes, goals));
    }

    public record Season(UUID id, String name, String status, Instant startAt, Instant endAt) {
    }

    /** {@code categoryName} y {@code headCoachName} son los de la asignación más reciente; nulos si nunca tuvo una. */
    public record Player(UUID id, String code, String fullName, LocalDate birthDate, String status, String categoryName,
                         String headCoachName) {
    }

    /** Intervalo semiabierto {@code [from, to)} cubierto por el informe; {@code partial} si la temporada aún no termina. */
    public record Period(Instant from, Instant to, boolean partial) {
    }

    public record Attendance(int sessions, int present, int late, int excused, int acceptedExcuses, int absent,
                             BigDecimal percentage) {
    }

    public record Evaluation(Instant closedAt, LocalDate periodStart, LocalDate periodEnd, String result,
                             String followUpPlan, List<Concept> concepts) {

        public Evaluation {
            concepts = List.copyOf(concepts);
        }
    }

    public record Concept(String evaluatorName, String verdict) {
    }

    public record MatchLine(Instant playedAt, String opponent, String tournament, Integer scoreFor, Integer scoreAgainst,
                            Integer minutes, Integer goals) {
    }

    public record Participation(int matches, int minutes, int goals) {
    }

    /** Copia congelada para {@code season_reports.snapshot}: solo textos, números y listas, sin tipos de fecha de Java. */
    public Map<String, Object> toSnapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("season", map("id", season.id(), "name", season.name(), "status", season.status(),
                "startAt", season.startAt(), "endAt", season.endAt()));
        root.put("player", map("id", player.id(), "code", player.code(), "fullName", player.fullName(),
                "birthDate", player.birthDate(), "status", player.status(), "category", player.categoryName(),
                "headCoach", player.headCoachName()));
        root.put("period", map("from", period.from(), "to", period.to(), "partial", period.partial()));
        root.put("attendance", map("sessions", attendance.sessions(), "present", attendance.present(),
                "late", attendance.late(), "excused", attendance.excused(), "acceptedExcuses", attendance.acceptedExcuses(),
                "absent", attendance.absent(), "percentage", attendance.percentage()));
        List<Object> rounds = new ArrayList<>();
        for (Evaluation e : evaluations) {
            List<Object> concepts = new ArrayList<>();
            for (Concept c : e.concepts()) {
                concepts.add(map("evaluator", c.evaluatorName(), "verdict", c.verdict()));
            }
            rounds.add(map("closedAt", e.closedAt(), "periodStart", e.periodStart(), "periodEnd", e.periodEnd(),
                    "result", e.result(), "followUpPlan", e.followUpPlan(), "concepts", concepts));
        }
        root.put("evaluations", rounds);
        List<Object> played = new ArrayList<>();
        for (MatchLine m : matches) {
            played.add(map("playedAt", m.playedAt(), "opponent", m.opponent(), "tournament", m.tournament(),
                    "scoreFor", m.scoreFor(), "scoreAgainst", m.scoreAgainst(), "minutes", m.minutes(), "goals", m.goals()));
        }
        root.put("matches", played);
        root.put("participation", map("matches", participation.matches(), "minutes", participation.minutes(),
                "goals", participation.goals()));
        return root;
    }

    /** Pares clave-valor; los valores no numéricos ni booleanos ni listas se guardan como texto (fechas en ISO-8601). */
    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            Object value = keysAndValues[i + 1];
            if (value == null) {
                continue;
            }
            boolean plain = value instanceof Number || value instanceof Boolean || value instanceof List;
            map.put((String) keysAndValues[i], plain ? value : value.toString());
        }
        return map;
    }
}
