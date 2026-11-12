package co.edu.unipamplona.canchatrack.attendance.infrastructure;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.attendance.application.AttendanceActor;
import co.edu.unipamplona.canchatrack.attendance.application.AttendanceService;
import co.edu.unipamplona.canchatrack.attendance.application.CorrectionCommand;
import co.edu.unipamplona.canchatrack.attendance.application.MarkInput;
import co.edu.unipamplona.canchatrack.attendance.infrastructure.AttendanceDtos.AttendanceResponse;
import co.edu.unipamplona.canchatrack.attendance.infrastructure.AttendanceDtos.CloseResponse;
import co.edu.unipamplona.canchatrack.attendance.infrastructure.AttendanceDtos.CorrectionRequest;
import co.edu.unipamplona.canchatrack.attendance.infrastructure.AttendanceDtos.CorrectionResponse;
import co.edu.unipamplona.canchatrack.attendance.infrastructure.AttendanceDtos.SummaryResponse;
import co.edu.unipamplona.canchatrack.attendance.infrastructure.AttendanceDtos.SyncRequest;
import co.edu.unipamplona.canchatrack.attendance.infrastructure.AttendanceDtos.SyncResponse;
import co.edu.unipamplona.canchatrack.shared.security.AuthenticatedUser;
import co.edu.unipamplona.canchatrack.shared.security.CurrentUser;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;

/** Asistencia por sesión y porcentaje por jugador (DDS UC-05; RN-15). */
@RestController
@RequestMapping(ApiPaths.V1)
public class AttendanceController {

    private final AttendanceService attendance;

    public AttendanceController(AttendanceService attendance) {
        this.attendance = attendance;
    }

    @GetMapping("/events/{eventId}/attendance")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE', 'COACH')")
    public AttendanceResponse view(@PathVariable UUID eventId) {
        AuthenticatedUser user = CurrentUser.require();
        return AttendanceResponse.from(attendance.view(user.id(), user.roles(), eventId));
    }

    /** Sincroniza un lote de marcas; reintentar con la misma {@code Idempotency-Key} no duplica nada. */
    @PostMapping("/events/{eventId}/attendance")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COACH')")
    public SyncResponse sync(@PathVariable UUID eventId,
                             @RequestHeader(value = ApiPaths.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
                             @RequestBody SyncRequest request, HttpServletRequest http) {
        List<MarkInput> marks = request.records() == null ? null : request.records().stream()
                .map(m -> new MarkInput(m.clientUuid(), m.playerId(), m.status(), Boolean.TRUE.equals(m.excuseAccepted()),
                        m.clientRecordedAt())).toList();
        return SyncResponse.from(attendance.sync(actor(http), eventId, idempotencyKey, marks));
    }

    @PostMapping("/events/{eventId}/attendance/close")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COACH')")
    public CloseResponse close(@PathVariable UUID eventId, HttpServletRequest http) {
        return CloseResponse.from(attendance.close(actor(http), eventId));
    }

    @PostMapping("/events/{eventId}/attendance/corrections")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COACH')")
    public CorrectionResponse correct(@PathVariable UUID eventId, @RequestBody CorrectionRequest request, HttpServletRequest http) {
        return CorrectionResponse.from(attendance.correct(actor(http), eventId, new CorrectionCommand(request.playerId(),
                request.status(), Boolean.TRUE.equals(request.excuseAccepted()), request.reason())));
    }

    @GetMapping("/players/{playerId}/attendance")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE', 'GUARDIAN', 'COACH')")
    public SummaryResponse summary(@PathVariable UUID playerId) {
        AuthenticatedUser user = CurrentUser.require();
        return SummaryResponse.from(attendance.summary(user.id(), user.roles(), playerId));
    }

    private static AttendanceActor actor(HttpServletRequest request) {
        AuthenticatedUser user = CurrentUser.require();
        return new AttendanceActor(user.id(), user.roles(), remoteAddress(request), request.getHeader("User-Agent"));
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
