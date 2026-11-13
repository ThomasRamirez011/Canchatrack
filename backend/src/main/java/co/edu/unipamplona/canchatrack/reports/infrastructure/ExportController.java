package co.edu.unipamplona.canchatrack.reports.infrastructure;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.reports.application.ExportFile;
import co.edu.unipamplona.canchatrack.reports.application.ExportService;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Exportaciones a CSV del ERS 7.3 que sustituyen las planillas del club (CU-12, CA-21): plantilla, asistencia,
 * convocatorias y resultados, y seguimiento de evaluación. Solo coordinación y comité; cada descarga queda auditada.
 */
@RestController
@RequestMapping(ApiPaths.V1)
public class ExportController {

    private final ExportService exports;

    public ExportController(ExportService exports) {
        this.exports = exports;
    }

    @GetMapping("/reports/roster/export")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE')")
    public ResponseEntity<byte[]> roster(@RequestParam(required = false) UUID categoryId, HttpServletRequest http) {
        return deliver(exports.roster(CsvResponses.actor(http), categoryId));
    }

    @GetMapping("/reports/attendance/export")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE')")
    public ResponseEntity<byte[]> attendance(@RequestParam(required = false) UUID seasonId,
                                             @RequestParam(required = false) UUID categoryId,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             HttpServletRequest http) {
        return deliver(exports.attendance(CsvResponses.actor(http), seasonId, categoryId, from, to));
    }

    @GetMapping("/reports/matches/export")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE')")
    public ResponseEntity<byte[]> matches(@RequestParam(required = false) UUID seasonId,
                                          @RequestParam(required = false) UUID categoryId,
                                          @RequestParam(required = false) UUID tournamentId,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                          HttpServletRequest http) {
        return deliver(exports.matches(CsvResponses.actor(http), seasonId, categoryId, tournamentId, from, to));
    }

    @GetMapping("/reports/evaluations/export")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE')")
    public ResponseEntity<byte[]> evaluations(@RequestParam(required = false) UUID seasonId,
                                              @RequestParam(required = false) UUID categoryId,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              HttpServletRequest http) {
        return deliver(exports.evaluations(CsvResponses.actor(http), seasonId, categoryId, from, to));
    }

    private static ResponseEntity<byte[]> deliver(ExportFile file) {
        return CsvResponses.csv(file.fileName(), file.csv());
    }
}
