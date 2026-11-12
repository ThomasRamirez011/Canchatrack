package co.edu.unipamplona.canchatrack.competitions.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Reglas puras de torneos, partidos, convocatoria y resultado (DDS 11.2). La hora actual la aporta quien llama. */
public final class CompetitionRules {

    public static final int MAX_NAME = 150;
    public static final int MAX_OPPONENT = 150;
    public static final int MAX_TEXT = 2000;
    public static final int MAX_OBSERVATIONS = 1000;
    /** Tamaño máximo de una convocatoria por solicitud (plantillas de fútbol base). */
    public static final int MAX_SQUAD = 40;
    public static final int MAX_MINUTES = 150;
    public static final int MAX_GOALS = 99;

    private CompetitionRules() {
    }

    public static List<FieldError> tournamentViolations(String name, LocalDate startDate, LocalDate endDate, String rules) {
        List<FieldError> errors = new ArrayList<>();
        if (name == null || name.isBlank()) {
            errors.add(new FieldError("name", "El nombre del torneo es obligatorio."));
        } else if (name.trim().length() > MAX_NAME) {
            errors.add(new FieldError("name", "El nombre no puede superar " + MAX_NAME + " caracteres."));
        }
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            errors.add(new FieldError("endDate", "La fecha final no puede ser anterior a la inicial."));
        }
        if (rules != null && rules.trim().length() > MAX_TEXT) {
            errors.add(new FieldError("rules", "El reglamento no puede superar " + MAX_TEXT + " caracteres."));
        }
        return errors;
    }

    public static List<FieldError> opponentViolations(String opponent) {
        List<FieldError> errors = new ArrayList<>();
        if (opponent == null || opponent.isBlank()) {
            errors.add(new FieldError("opponent", "El rival es obligatorio."));
        } else if (opponent.trim().length() > MAX_OPPONENT) {
            errors.add(new FieldError("opponent", "El rival no puede superar " + MAX_OPPONENT + " caracteres."));
        }
        return errors;
    }

    /**
     * Un partido de torneo debe coincidir con la categoría del torneo (si la declara) y caer dentro de sus fechas (si las
     * declara).
     */
    public static List<FieldError> tournamentFitViolations(UUID matchCategoryId, UUID tournamentCategoryId, LocalDate matchDate,
                                                            LocalDate tournamentStart, LocalDate tournamentEnd) {
        List<FieldError> errors = new ArrayList<>();
        if (tournamentCategoryId != null && matchCategoryId != null && !tournamentCategoryId.equals(matchCategoryId)) {
            errors.add(new FieldError("categoryId", "La categoría del partido no coincide con la del torneo."));
        }
        if (matchDate != null && ((tournamentStart != null && matchDate.isBefore(tournamentStart))
                || (tournamentEnd != null && matchDate.isAfter(tournamentEnd)))) {
            errors.add(new FieldError("startAt", "El partido debe jugarse dentro de las fechas del torneo."));
        }
        return errors;
    }

    /** Lista de jugadores de una convocatoria o de una confirmación: no vacía, sin nulos ni repetidos, con tope. */
    public static List<FieldError> squadViolations(List<UUID> playerIds) {
        List<FieldError> errors = new ArrayList<>();
        if (playerIds == null || playerIds.isEmpty()) {
            errors.add(new FieldError("playerIds", "Indique al menos un jugador."));
            return errors;
        }
        if (playerIds.size() > MAX_SQUAD) {
            errors.add(new FieldError("playerIds", "No puede indicar más de " + MAX_SQUAD + " jugadores por solicitud."));
        }
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < playerIds.size(); i++) {
            UUID id = playerIds.get(i);
            if (id == null) {
                errors.add(new FieldError("playerIds[" + i + "]", "El identificador del jugador es obligatorio."));
            } else if (!seen.add(id)) {
                errors.add(new FieldError("playerIds[" + i + "]", "El jugador está repetido."));
            }
        }
        return errors;
    }

    /**
     * Resultado del partido (RF-036). El marcador es obligatorio; los goles individuales no pueden superar los del equipo.
     * Si ya había un resultado, la corrección exige motivo (queda auditada).
     */
    public static List<FieldError> resultViolations(Integer scoreFor, Integer scoreAgainst, String notes,
                                                    List<PlayerResultLine> lines, boolean correction, String reason) {
        List<FieldError> errors = new ArrayList<>();
        if (scoreFor == null) {
            errors.add(new FieldError("scoreFor", "Los goles a favor son obligatorios."));
        } else if (scoreFor < 0 || scoreFor > MAX_GOALS) {
            errors.add(new FieldError("scoreFor", "Los goles a favor deben estar entre 0 y " + MAX_GOALS + "."));
        }
        if (scoreAgainst == null) {
            errors.add(new FieldError("scoreAgainst", "Los goles en contra son obligatorios."));
        } else if (scoreAgainst < 0 || scoreAgainst > MAX_GOALS) {
            errors.add(new FieldError("scoreAgainst", "Los goles en contra deben estar entre 0 y " + MAX_GOALS + "."));
        }
        if (notes != null && notes.trim().length() > MAX_TEXT) {
            errors.add(new FieldError("resultNotes", "Las observaciones no pueden superar " + MAX_TEXT + " caracteres."));
        }
        long individualGoals = 0;
        Set<UUID> seen = new HashSet<>();
        if (lines != null) {
            for (int i = 0; i < lines.size(); i++) {
                PlayerResultLine line = lines.get(i);
                String prefix = "players[" + i + "].";
                if (line == null || line.playerId() == null) {
                    errors.add(new FieldError(prefix + "playerId", "El jugador es obligatorio."));
                    continue;
                }
                if (!seen.add(line.playerId())) {
                    errors.add(new FieldError(prefix + "playerId", "El jugador está repetido."));
                }
                if (line.minutesPlayed() != null && (line.minutesPlayed() < 0 || line.minutesPlayed() > MAX_MINUTES)) {
                    errors.add(new FieldError(prefix + "minutesPlayed", "Los minutos deben estar entre 0 y " + MAX_MINUTES + "."));
                }
                if (line.goals() != null) {
                    if (line.goals() < 0 || line.goals() > MAX_GOALS) {
                        errors.add(new FieldError(prefix + "goals", "Los goles deben estar entre 0 y " + MAX_GOALS + "."));
                    } else {
                        individualGoals += line.goals();
                    }
                }
                if (line.observations() != null && line.observations().trim().length() > MAX_OBSERVATIONS) {
                    errors.add(new FieldError(prefix + "observations", "Las observaciones no pueden superar " + MAX_OBSERVATIONS + " caracteres."));
                }
            }
        }
        if (scoreFor != null && scoreFor >= 0 && individualGoals > scoreFor) {
            errors.add(new FieldError("players", "La suma de goles individuales supera los goles a favor del equipo."));
        }
        if (correction) {
            if (reason == null || reason.isBlank()) {
                errors.add(new FieldError("reason", "El motivo es obligatorio para corregir un resultado ya registrado."));
            } else if (reason.trim().length() > MAX_TEXT) {
                errors.add(new FieldError("reason", "El motivo no puede superar " + MAX_TEXT + " caracteres."));
            }
        }
        return errors;
    }

    /** Se convoca y se confirma solo antes de que el partido comience. */
    public static boolean callupWindowOpen(Instant startAt, Instant now) {
        return startAt.isAfter(now);
    }

    /** El resultado se registra desde que el partido comienza. */
    public static boolean resultWindowOpen(Instant startAt, Instant now) {
        return !startAt.isAfter(now);
    }
}
