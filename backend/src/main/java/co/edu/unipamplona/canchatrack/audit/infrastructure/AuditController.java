package co.edu.unipamplona.canchatrack.audit.infrastructure;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.audit.application.AuditActor;
import co.edu.unipamplona.canchatrack.audit.application.AuditQueryService;
import co.edu.unipamplona.canchatrack.audit.application.AuditSearch;
import co.edu.unipamplona.canchatrack.audit.infrastructure.AuditDtos.AuditEventPageResponse;
import co.edu.unipamplona.canchatrack.shared.security.CurrentUser;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;

/** Consulta de la bitácora de auditoría (CU-14, RF-051). Solo la coordinación; no hay operaciones de escritura. */
@RestController
@RequestMapping(ApiPaths.V1)
public class AuditController {

    private final AuditQueryService audit;

    public AuditController(AuditQueryService audit) {
        this.audit = audit;
    }

    @GetMapping("/audit/events")
    @PreAuthorize("hasRole('COORDINATOR')")
    public AuditEventPageResponse events(@RequestParam(required = false) String action,
                                         @RequestParam(required = false) String entityType,
                                         @RequestParam(required = false) UUID entityId,
                                         @RequestParam(required = false) UUID actorId,
                                         @RequestParam(required = false) String outcome,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
                                         @RequestParam(required = false) Integer limit,
                                         @RequestParam(required = false) String cursor,
                                         HttpServletRequest http) {
        AuditActor actor = new AuditActor(CurrentUser.require().id(), remoteAddress(http), http.getHeader("User-Agent"));
        return AuditEventPageResponse.from(audit.search(actor, new AuditSearch(action, entityType, entityId, actorId,
                outcome, from == null ? null : from.toInstant(), to == null ? null : to.toInstant(), limit, cursor)));
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
