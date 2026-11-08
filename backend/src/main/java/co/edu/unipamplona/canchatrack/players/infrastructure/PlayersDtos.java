package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.players.application.EnrollmentReceipt;
import co.edu.unipamplona.canchatrack.players.application.EnrollmentSummary;
import co.edu.unipamplona.canchatrack.players.application.PageResult;
import co.edu.unipamplona.canchatrack.players.application.PlayerDetail;
import co.edu.unipamplona.canchatrack.players.application.PlayerSummary;
import co.edu.unipamplona.canchatrack.players.application.PlayerWithEnrollments;
import co.edu.unipamplona.canchatrack.players.domain.CallInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Contratos JSON de convocatorias, inscripción y jugadores (ver openapi/canchatrack-v1.yaml). */
public final class PlayersDtos {

    private PlayersDtos() {
    }

    public record PlayerData(
            @NotBlank(message = "El nombre es obligatorio.") @Size(max = 100, message = "El nombre es demasiado largo.") String firstName,
            @NotBlank(message = "El apellido es obligatorio.") @Size(max = 100, message = "El apellido es demasiado largo.") String lastName,
            @NotBlank(message = "El tipo de documento es obligatorio.") @Size(max = 10, message = "Tipo de documento no válido.") String documentType,
            @NotBlank(message = "El número de documento es obligatorio.") @Size(max = 30, message = "El número de documento es demasiado largo.") String documentNumber,
            @NotNull(message = "La fecha de nacimiento es obligatoria.") LocalDate birthDate) {
    }

    public record SubmitEnrollmentRequest(
            @NotNull(message = "La convocatoria es obligatoria.") UUID callId,
            @NotNull(message = "Los datos del jugador son obligatorios.") @Valid PlayerData player,
            @Size(max = 40, message = "El parentesco no puede superar 40 caracteres.") String relationship,
            @NotNull(message = "Debe indicar si acepta el tratamiento de datos.") Boolean acceptDataProcessing,
            @NotNull(message = "Debe indicar si otorga la autorización de participación.") Boolean acceptParticipation) {
    }

    public record EnrollmentResponse(UUID enrollmentId, String filingCode, UUID playerId, String playerCode,
                                     String playerStatus, UUID callId, Instant submittedAt, boolean viaException) {

        static EnrollmentResponse from(EnrollmentReceipt receipt) {
            return new EnrollmentResponse(receipt.enrollmentId(), receipt.filingCode(), receipt.playerId(),
                    receipt.playerCode(), receipt.playerStatus().name(), receipt.callId(), receipt.submittedAt(),
                    receipt.viaException());
        }
    }

    public record CallResponse(UUID id, String name, Instant startAt, Instant endAt, String instructions) {

        static CallResponse from(CallInfo call) {
            return new CallResponse(call.id(), call.name(), call.startAt(), call.endAt(), call.instructions());
        }
    }

    public record PlayerSummaryResponse(UUID id, String code, String firstName, String lastName, LocalDate birthDate,
                                        String status) {

        static PlayerSummaryResponse from(PlayerSummary player) {
            return new PlayerSummaryResponse(player.id(), player.code(), player.firstName(), player.lastName(),
                    player.birthDate(), player.status().name());
        }
    }

    public record PlayerPage(List<PlayerSummaryResponse> items, int page, int size, long total) {

        static PlayerPage from(PageResult<PlayerSummary> result) {
            return new PlayerPage(result.items().stream().map(PlayerSummaryResponse::from).toList(), result.page(),
                    result.size(), result.total());
        }
    }

    public record EnrollmentSummaryResponse(UUID id, String filingCode, UUID callId, String callName, Instant submittedAt) {

        static EnrollmentSummaryResponse from(EnrollmentSummary enrollment) {
            return new EnrollmentSummaryResponse(enrollment.id(), enrollment.filingCode(), enrollment.callId(),
                    enrollment.callName(), enrollment.submittedAt());
        }
    }

    public record PlayerDetailResponse(UUID id, String code, String firstName, String lastName, String documentType,
                                       String documentNumber, LocalDate birthDate, String status, Instant createdAt,
                                       List<EnrollmentSummaryResponse> enrollments) {

        static PlayerDetailResponse from(PlayerWithEnrollments view) {
            PlayerDetail p = view.player();
            return new PlayerDetailResponse(p.id(), p.code(), p.firstName(), p.lastName(), p.documentType(),
                    p.documentNumber(), p.birthDate(), p.status().name(), p.createdAt(),
                    view.enrollments().stream().map(EnrollmentSummaryResponse::from).toList());
        }
    }
}
