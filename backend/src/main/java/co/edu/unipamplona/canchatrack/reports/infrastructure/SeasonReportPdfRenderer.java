package co.edu.unipamplona.canchatrack.reports.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.springframework.stereotype.Component;

import co.edu.unipamplona.canchatrack.reports.application.SeasonReportContent;
import co.edu.unipamplona.canchatrack.reports.application.SeasonReportFacts;
import co.edu.unipamplona.canchatrack.reports.application.SeasonReportRenderer;

/**
 * Plantilla provisional del informe de temporada (P-09 sin definir): identificación del informe, jugador, temporada,
 * asistencia, evaluaciones publicadas y participación en partidos. No incluye documento de identidad, datos de salud ni las
 * justificaciones de los evaluadores.
 */
@Component
public class SeasonReportPdfRenderer implements SeasonReportRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final Map<String, String> VERDICTS = Map.of(
            "FAVORABLE", "Favorable",
            "FOLLOW_UP_REQUIRED", "Seguimiento requerido",
            "UNFAVORABLE", "No favorable");
    private static final Map<String, String> ROUND_RESULTS = Map.of(
            "APPROVED", "Ascenso avalado",
            "FOLLOW_UP", "Seguimiento");
    private static final Map<String, String> PLAYER_STATES = Map.ofEntries(
            Map.entry("ACTIVE", "Activo"),
            Map.entry("IN_EVALUATION", "En evaluación"),
            Map.entry("PROMOTION_APPROVED", "Ascenso aprobado"),
            Map.entry("SUSPENDED", "Suspendido"),
            Map.entry("WITHDRAWN", "Retirado"),
            Map.entry("SEASON_CLOSED", "Temporada cerrada"));

    @Override
    public byte[] render(SeasonReportContent c) {
        ZoneId zone = c.zone();
        SeasonReportFacts f = c.facts();
        SimplePdf pdf = new SimplePdf("Informe de temporada " + c.number(),
                "Informe " + c.number() + " · versión " + c.version() + " · verificación " + c.verificationCode(), c.generatedAt());

        pdf.heading("Informe de temporada", 20f);
        pdf.field("Informe N.º", c.number(), 10f);
        pdf.field("Versión", c.version() + (c.reason() == null ? "" : " (regenerado: " + c.reason() + ")"), 10f);
        pdf.field("Generado", DATE_TIME.withZone(zone).format(c.generatedAt()), 10f);
        pdf.field("Código de verificación", c.verificationCode(), 10f);

        pdf.heading("Jugador", 13f);
        SeasonReportFacts.Player p = f.player();
        pdf.field("Nombre", p.fullName(), 10f);
        pdf.field("Código", p.code(), 10f);
        pdf.field("Fecha de nacimiento", p.birthDate() == null ? null : DATE.format(p.birthDate()), 10f);
        pdf.field("Categoría", p.categoryName(), 10f);
        pdf.field("Entrenador titular", p.headCoachName(), 10f);
        pdf.field("Estado del expediente", PLAYER_STATES.getOrDefault(p.status(), p.status()), 10f);

        pdf.heading("Temporada", 13f);
        pdf.field("Nombre", f.season().name(), 10f);
        pdf.field("Periodo", day(f.season().startAt(), zone) + " a " + day(f.season().endAt(), zone), 10f);
        if (f.period().partial()) {
            pdf.field("Cobertura", "Informe parcial: cubre hasta el " + day(f.period().to(), zone), 10f);
        }

        pdf.heading("Asistencia", 13f);
        SeasonReportFacts.Attendance a = f.attendance();
        if (a.sessions() == 0) {
            pdf.paragraph("No hay sesiones cerradas con registro en el periodo.", 10f, false);
        } else {
            pdf.field("Sesiones registradas", String.valueOf(a.sessions()), 10f);
            pdf.field("Presente", String.valueOf(a.present()), 10f);
            pdf.field("Tarde", String.valueOf(a.late()), 10f);
            pdf.field("Excusa", a.excused() + " (aceptadas: " + a.acceptedExcuses() + ")", 10f);
            pdf.field("Ausente", String.valueOf(a.absent()), 10f);
            pdf.field("Porcentaje de asistencia", percentage(a.percentage()), 10f);
        }

        pdf.heading("Evaluaciones publicadas", 13f);
        if (f.evaluations().isEmpty()) {
            pdf.paragraph("Sin evaluaciones cerradas en el periodo.", 10f, false);
        }
        for (SeasonReportFacts.Evaluation e : f.evaluations()) {
            pdf.space(4f);
            pdf.paragraph("Ronda cerrada el " + day(e.closedAt(), zone) + " · periodo evaluado "
                    + DATE.format(e.periodStart()) + " a " + DATE.format(e.periodEnd()) + " · resultado: "
                    + ROUND_RESULTS.getOrDefault(e.result(), e.result()), 10f, true);
            for (SeasonReportFacts.Concept concept : e.concepts()) {
                pdf.paragraph("   " + concept.evaluatorName() + ": " + VERDICTS.getOrDefault(concept.verdict(), concept.verdict()),
                        10f, false);
            }
            if (e.followUpPlan() != null && !e.followUpPlan().isBlank()) {
                pdf.paragraph("   Plan de seguimiento: " + e.followUpPlan(), 10f, false);
            }
        }

        pdf.heading("Participación en partidos", 13f);
        SeasonReportFacts.Participation part = f.participation();
        if (f.matches().isEmpty()) {
            pdf.paragraph("Sin partidos jugados en el periodo.", 10f, false);
        } else {
            pdf.field("Partidos", String.valueOf(part.matches()), 10f);
            pdf.field("Minutos jugados", String.valueOf(part.minutes()), 10f);
            pdf.field("Goles", String.valueOf(part.goals()), 10f);
            pdf.space(4f);
            float[] columns = {0f, 62f, 190f, 330f, 400f, 450f};
            pdf.row(columns, new String[]{"Fecha", "Rival", "Torneo", "Marcador", "Min.", "Goles"}, 9f, true);
            for (SeasonReportFacts.MatchLine m : f.matches()) {
                pdf.row(columns, new String[]{day(m.playedAt(), zone), m.opponent(), m.tournament() == null ? "Amistoso" : m.tournament(),
                        m.scoreFor() == null ? "—" : m.scoreFor() + " - " + m.scoreAgainst(),
                        m.minutes() == null ? "—" : String.valueOf(m.minutes()), m.goals() == null ? "—" : String.valueOf(m.goals())},
                        9f, false);
            }
        }

        pdf.space(14f);
        pdf.paragraph("Documento emitido por CanchaTrack (" + c.templateVersion() + ", formato provisional). Para comprobar su "
                + "autenticidad, consulte el código de verificación con la coordinación del club.", 8f, false);
        return pdf.build();
    }

    private static String day(Instant instant, ZoneId zone) {
        return DATE.withZone(zone).format(instant);
    }

    private static String percentage(BigDecimal value) {
        return value == null ? "No calculable (sin sesiones contables)" : value.toPlainString().replace('.', ',') + " %";
    }
}
