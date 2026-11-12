package co.edu.unipamplona.canchatrack.reports.infrastructure;

import java.time.Instant;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.reports.application.SeasonClosure;
import co.edu.unipamplona.canchatrack.reports.application.SeasonReport;
import co.edu.unipamplona.canchatrack.reports.application.SeasonReportService;

/** Contratos JSON del informe de temporada y del cierre del expediente (ver openapi/canchatrack-v1.yaml). */
final class SeasonReportDtos {

    private SeasonReportDtos() {
    }

    record SeasonReportRequest(UUID seasonId, String reason) {
    }

    record SeasonReportResponse(UUID id, UUID playerId, UUID seasonId, String number, long consecutive, int version,
                                UUID previousReportId, String reason, String verificationCode, String templateVersion,
                                UUID fileId, String fileHash, Instant generatedAt, UUID generatedBy) {

        static SeasonReportResponse from(SeasonReport r) {
            return new SeasonReportResponse(r.id(), r.playerId(), r.seasonId(), r.number(), r.consecutive(), r.version(),
                    r.previousReportId(), r.reason(), r.verificationCode(), r.templateVersion(), r.fileId(), r.fileHash(),
                    r.generatedAt(), r.generatedBy());
        }
    }

    record SeasonReportVerificationResponse(boolean current, SeasonReportResponse report) {

        static SeasonReportVerificationResponse from(SeasonReportService.Verification v) {
            return new SeasonReportVerificationResponse(v.current(), SeasonReportResponse.from(v.report()));
        }
    }

    record SeasonClosureRequest(UUID seasonId, UUID reportId) {
    }

    record SeasonReopenRequest(String reason, String detail, String confirmation) {
    }

    record SeasonClosureResponse(UUID id, UUID playerId, UUID seasonId, UUID reportId, UUID closedBy, Instant closedAt,
                                 boolean open, UUID reopenedBy, Instant reopenedAt, String reopenReason, String reopenDetail) {

        static SeasonClosureResponse from(SeasonClosure c) {
            return new SeasonClosureResponse(c.id(), c.playerId(), c.seasonId(), c.reportId(), c.closedBy(), c.closedAt(),
                    c.open(), c.reopenedBy(), c.reopenedAt(), c.reopenReason(), c.reopenDetail());
        }
    }
}
