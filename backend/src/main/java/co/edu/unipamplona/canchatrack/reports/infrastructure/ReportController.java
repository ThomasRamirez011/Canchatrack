package co.edu.unipamplona.canchatrack.reports.infrastructure;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.reports.application.ReceivablesCsv;
import co.edu.unipamplona.canchatrack.reports.application.ReceivablesReport;
import co.edu.unipamplona.canchatrack.reports.application.ReportService;
import co.edu.unipamplona.canchatrack.reports.application.ReportsActor;
import co.edu.unipamplona.canchatrack.reports.infrastructure.ReportDtos.ReceivablesReportResponse;
import co.edu.unipamplona.canchatrack.reports.infrastructure.ReportDtos.SeasonSummaryResponse;
import co.edu.unipamplona.canchatrack.shared.security.AuthenticatedUser;
import co.edu.unipamplona.canchatrack.shared.security.CurrentUser;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;

/** Cartera por categoría y resumen de temporada (CU-10 a CU-12; RF-042). Solo coordinación y comité, solo lectura. */
@RestController
@RequestMapping(ApiPaths.V1)
public class ReportController {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");
    /** Marca de orden de bytes: sin ella Excel abre el CSV UTF-8 con las tildes rotas. */
    private static final String BOM = "﻿";

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/reports/receivables")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE')")
    public ReceivablesReportResponse receivables(@RequestParam(required = false) UUID seasonId,
                                                 @RequestParam(required = false) UUID categoryId,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ReceivablesReportResponse.from(reports.receivables(seasonId, categoryId, asOf));
    }

    /** Exportación de la cartera a CSV (abre en Excel). Distinta de la consulta porque deja rastro de auditoría. */
    @GetMapping("/reports/receivables/export")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE')")
    public ResponseEntity<byte[]> exportReceivables(@RequestParam(required = false) UUID seasonId,
                                                    @RequestParam(required = false) UUID categoryId,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                                                    HttpServletRequest http) {
        AuthenticatedUser user = CurrentUser.require();
        String role = user.roles().contains("COORDINATOR") ? "COORDINATOR" : "COMMITTEE";
        ReceivablesReport report = reports.exportReceivables(
                new ReportsActor(user.id(), role, remoteAddress(http), http.getHeader("User-Agent")), seasonId, categoryId, asOf);
        byte[] body = (BOM + ReceivablesCsv.render(report)).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(CSV)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("cartera-" + report.asOf() + ".csv").build().toString())
                .body(body);
    }

    @GetMapping("/reports/season-summary")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE')")
    public SeasonSummaryResponse seasonSummary(@RequestParam(required = false) UUID seasonId) {
        return SeasonSummaryResponse.from(reports.seasonSummary(seasonId));
    }

    private static InetAddress remoteAddress(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) {
            return null;
        }
        try {
            return InetAddress.getByName(remote);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
