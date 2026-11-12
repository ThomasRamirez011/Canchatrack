package co.edu.unipamplona.canchatrack.attendance.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.attendance.application.AttendanceView;
import co.edu.unipamplona.canchatrack.attendance.application.CloseResult;
import co.edu.unipamplona.canchatrack.attendance.application.PlayerAttendanceSummary;
import co.edu.unipamplona.canchatrack.attendance.application.RecordInfo;
import co.edu.unipamplona.canchatrack.attendance.application.RosterEntry;
import co.edu.unipamplona.canchatrack.attendance.application.SyncResult;

/** Contratos JSON de asistencia. Las solicitudes no llevan Bean Validation: las reglas viven en el dominio. */
final class AttendanceDtos {

    private AttendanceDtos() {
    }

    record MarkRequest(UUID clientUuid, UUID playerId, String status, Boolean excuseAccepted, Instant clientRecordedAt) {
    }

    record SyncRequest(List<MarkRequest> records) {
    }

    record CorrectionRequest(UUID playerId, String status, Boolean excuseAccepted, String reason) {
    }

    record RejectionResponse(UUID clientUuid, UUID playerId, String reason) {
    }

    record SyncResponse(boolean replayed, int received, int accepted, int duplicates, int rejected,
                        List<RejectionResponse> rejections) {

        static SyncResponse from(SyncResult r) {
            return new SyncResponse(r.replayed(), r.received(), r.accepted(), r.duplicates(), r.rejected(),
                    r.rejections().stream().map(x -> new RejectionResponse(x.clientUuid(), x.playerId(), x.reason())).toList());
        }
    }

    record RosterEntryResponse(UUID playerId, String code, String firstName, String lastName, String status, boolean excuseAccepted) {

        static RosterEntryResponse from(RosterEntry e) {
            return new RosterEntryResponse(e.playerId(), e.code(), e.firstName(), e.lastName(), e.status(), e.excuseAccepted());
        }
    }

    record AttendanceResponse(UUID eventId, boolean closed, Instant closedAt, List<RosterEntryResponse> entries) {

        static AttendanceResponse from(AttendanceView v) {
            return new AttendanceResponse(v.eventId(), v.closed(), v.closedAt(), v.entries().stream().map(RosterEntryResponse::from).toList());
        }
    }

    record CloseResponse(UUID eventId, Instant closedAt, int autoAbsent, int total) {

        static CloseResponse from(CloseResult r) {
            return new CloseResponse(r.eventId(), r.closedAt(), r.autoAbsent(), r.total());
        }
    }

    record CorrectionResponse(UUID recordId, UUID playerId, String status, boolean excuseAccepted) {

        static CorrectionResponse from(RecordInfo r) {
            return new CorrectionResponse(r.id(), r.playerId(), r.status(), r.excuseAccepted());
        }
    }

    record SummaryResponse(UUID playerId, int sessions, int present, int late, int excused, int acceptedExcuses, int absent,
                           BigDecimal percentage) {

        static SummaryResponse from(PlayerAttendanceSummary s) {
            return new SummaryResponse(s.playerId(), s.sessions(), s.present(), s.late(), s.excused(), s.acceptedExcuses(),
                    s.absent(), s.percentage());
        }
    }
}
