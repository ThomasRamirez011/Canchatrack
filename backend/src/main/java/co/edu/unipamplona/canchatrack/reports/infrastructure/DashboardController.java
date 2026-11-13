package co.edu.unipamplona.canchatrack.reports.infrastructure;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.reports.application.DashboardService;
import co.edu.unipamplona.canchatrack.reports.infrastructure.DashboardDtos.ClubOverviewResponse;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;

/** Tablero del club: una lectura con lo que muestra la pantalla principal. Solo coordinación y comité, solo lectura. */
@RestController
@RequestMapping(ApiPaths.V1)
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/dashboard/club")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE')")
    public ClubOverviewResponse club() {
        return ClubOverviewResponse.from(dashboard.overview());
    }
}
