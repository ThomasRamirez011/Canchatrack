package co.edu.unipamplona.canchatrack.reports.infrastructure;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.reports.application.ReportsActor;
import co.edu.unipamplona.canchatrack.reports.application.SeasonClosureService;
import co.edu.unipamplona.canchatrack.reports.application.SeasonReportService;
import co.edu.unipamplona.canchatrack.reports.infrastructure.SeasonReportDtos.SeasonClosureRequest;
import co.edu.unipamplona.canchatrack.reports.infrastructure.SeasonReportDtos.SeasonClosureResponse;
import co.edu.unipamplona.canchatrack.reports.infrastructure.SeasonReportDtos.SeasonReopenRequest;
import co.edu.unipamplona.canchatrack.reports.infrastructure.SeasonReportDtos.SeasonReportRequest;
import co.edu.unipamplona.canchatrack.reports.infrastructure.SeasonReportDtos.SeasonReportResponse;
import co.edu.unipamplona.canchatrack.reports.infrastructure.SeasonReportDtos.SeasonReportVerificationResponse;
import co.edu.unipamplona.canchatrack.shared.security.AuthenticatedUser;
import co.edu.unipamplona.canchatrack.shared.security.CurrentUser;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Informe de temporada por jugador y cierre o reapertura del expediente (CU-10, CU-11; RF-049, RF-050, RN-18). Generar,
 * cerrar y reabrir son solo de la coordinación; la consulta y la verificación también del comité. El PDF se descarga con
 * {@code GET /files/{fileId}}.
 */
@RestController
@RequestMapping(ApiPaths.V1)
public class SeasonReportController {

    private final SeasonReportService reports;
    private final SeasonClosureService closures;

    public SeasonReportController(SeasonReportService reports, SeasonClosureService closures) {
        this.reports = reports;
        this.closures = closures;
    }

    @PostMapping("/players/{playerId}/season-reports")
    @PreAuthorize("hasRole('COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public SeasonReportResponse generate(@PathVariable UUID playerId, @RequestBody SeasonReportRequest request,
                                         HttpServletRequest http) {
        return SeasonReportResponse.from(reports.generate(actor(http), playerId, request.seasonId(), request.reason()));
    }

    @GetMapping("/players/{playerId}/season-reports")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE')")
    public List<SeasonReportResponse> list(@PathVariable UUID playerId, @RequestParam(required = false) UUID seasonId) {
        return reports.list(playerId, seasonId).stream().map(SeasonReportResponse::from).toList();
    }

    @GetMapping("/season-reports/{reportId}")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE')")
    public SeasonReportResponse get(@PathVariable UUID reportId) {
        return SeasonReportResponse.from(reports.get(reportId));
    }

    /** Comprueba el código impreso en un PDF: informe al que pertenece y si es la versión vigente. */
    @GetMapping("/season-reports/verify")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE')")
    public SeasonReportVerificationResponse verify(@RequestParam String code) {
        return SeasonReportVerificationResponse.from(reports.verify(code));
    }

    @PostMapping("/players/{playerId}/season-closure")
    @PreAuthorize("hasRole('COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public SeasonClosureResponse close(@PathVariable UUID playerId, @RequestBody SeasonClosureRequest request,
                                       HttpServletRequest http) {
        return SeasonClosureResponse.from(closures.close(actor(http), playerId, request.seasonId(), request.reportId()));
    }

    @PostMapping("/players/{playerId}/season-closure/reopen")
    @PreAuthorize("hasRole('COORDINATOR')")
    public SeasonClosureResponse reopen(@PathVariable UUID playerId, @RequestBody SeasonReopenRequest request,
                                        HttpServletRequest http) {
        return SeasonClosureResponse.from(closures.reopen(actor(http), playerId, request.reason(), request.detail(),
                request.confirmation()));
    }

    /** Último cierre del jugador, vigente o ya reabierto; 404 si nunca se cerró. */
    @GetMapping("/players/{playerId}/season-closure")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE')")
    public SeasonClosureResponse closure(@PathVariable UUID playerId) {
        return SeasonClosureResponse.from(closures.latest(playerId));
    }

    private static ReportsActor actor(HttpServletRequest http) {
        AuthenticatedUser user = CurrentUser.require();
        return new ReportsActor(user.id(), "COORDINATOR", remoteAddress(http), http.getHeader("User-Agent"));
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
