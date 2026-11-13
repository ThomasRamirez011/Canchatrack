package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.players.application.CallDetail;
import co.edu.unipamplona.canchatrack.players.application.CallExceptionInfo;
import co.edu.unipamplona.canchatrack.players.application.CategoryInfo;
import co.edu.unipamplona.canchatrack.players.application.AssignmentView;
import co.edu.unipamplona.canchatrack.players.application.CategorySuggestion;
import co.edu.unipamplona.canchatrack.players.application.DecisionResult;
import co.edu.unipamplona.canchatrack.players.application.EnrollmentReceipt;
import co.edu.unipamplona.canchatrack.players.application.EnrollmentSummary;
import co.edu.unipamplona.canchatrack.players.application.MonthlyBillingResult;
import co.edu.unipamplona.canchatrack.players.application.RegistrationResult;
import co.edu.unipamplona.canchatrack.players.application.PageResult;
import co.edu.unipamplona.canchatrack.players.application.PlayerDetail;
import co.edu.unipamplona.canchatrack.players.application.PlayerStatusChange;
import co.edu.unipamplona.canchatrack.players.application.PlayerSummary;
import co.edu.unipamplona.canchatrack.players.application.PlayerWithEnrollments;
import co.edu.unipamplona.canchatrack.players.application.SeasonInfo;
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
            @NotNull(message = "Debe indicar si otorga la autorización de participación.") Boolean acceptParticipation,
            List<EnrollmentDocumentRequest> documents) {
    }

    /** Documento inicial: tipo del catálogo y archivo ya subido con {@code POST /files}. Se valida en el servicio. */
    public record EnrollmentDocumentRequest(UUID documentTypeId, UUID fileId, LocalDate issuedOn, LocalDate expiresOn,
                                            String notes) {
    }

    public record EnrollmentResponse(UUID enrollmentId, String filingCode, UUID playerId, String playerCode,
                                     String playerStatus, UUID callId, Instant submittedAt, boolean viaException,
                                     int attachedDocuments, List<String> missingDocuments) {

        static EnrollmentResponse from(EnrollmentReceipt receipt) {
            return new EnrollmentResponse(receipt.enrollmentId(), receipt.filingCode(), receipt.playerId(),
                    receipt.playerCode(), receipt.playerStatus().name(), receipt.callId(), receipt.submittedAt(),
                    receipt.viaException(), receipt.attachedDocuments(), receipt.missingDocuments());
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

    // --- Administración de temporadas y convocatorias (coordinador) ---

    public record CreateSeasonRequest(
            @NotBlank(message = "El nombre es obligatorio.") @Size(max = 100, message = "El nombre no puede superar 100 caracteres.") String name,
            @NotNull(message = "La fecha de inicio es obligatoria.") Instant startAt,
            @NotNull(message = "La fecha de fin es obligatoria.") Instant endAt) {
    }

    public record SeasonResponse(UUID id, String name, Instant startAt, Instant endAt, String status, Instant createdAt) {

        static SeasonResponse from(SeasonInfo season) {
            return new SeasonResponse(season.id(), season.name(), season.startAt(), season.endAt(),
                    season.status().name(), season.createdAt());
        }
    }

    public record CreateCallRequest(
            @NotNull(message = "La temporada es obligatoria.") UUID seasonId,
            @NotBlank(message = "El nombre es obligatorio.") @Size(max = 150, message = "El nombre no puede superar 150 caracteres.") String name,
            @NotNull(message = "La fecha de apertura es obligatoria.") Instant startAt,
            @NotNull(message = "La fecha de cierre es obligatoria.") Instant endAt,
            @Size(max = 4000, message = "Las instrucciones no pueden superar 4000 caracteres.") String instructions) {
    }

    public record UpdateCallRequest(
            @Size(max = 150, message = "El nombre no puede superar 150 caracteres.") String name,
            Instant startAt,
            Instant endAt,
            @Size(max = 4000, message = "Las instrucciones no pueden superar 4000 caracteres.") String instructions,
            @Size(max = 500, message = "El motivo no puede superar 500 caracteres.") String reason) {
    }

    public record CancelCallRequest(
            @NotBlank(message = "El motivo es obligatorio.") @Size(max = 500, message = "El motivo no puede superar 500 caracteres.") String reason) {
    }

    public record GrantExceptionRequest(
            @NotNull(message = "El acudiente es obligatorio.") UUID guardianId,
            @NotBlank(message = "El motivo es obligatorio.") @Size(max = 500, message = "El motivo no puede superar 500 caracteres.") String reason,
            @NotNull(message = "La fecha de vencimiento es obligatoria.") Instant expiresAt) {
    }

    public record CallDetailResponse(UUID id, UUID seasonId, String name, Instant startAt, Instant endAt,
                                     String instructions, String status, boolean windowOpen, Instant createdAt,
                                     Instant updatedAt) {

        static CallDetailResponse from(CallDetail call) {
            return new CallDetailResponse(call.id(), call.seasonId(), call.name(), call.startAt(), call.endAt(),
                    call.instructions(), call.status().name(), call.windowOpen(), call.createdAt(), call.updatedAt());
        }
    }

    public record CallExceptionResponse(UUID id, UUID callId, UUID guardianId, String reason, UUID grantedBy,
                                        Instant grantedAt, Instant expiresAt, boolean active) {

        static CallExceptionResponse from(CallExceptionInfo exception) {
            return new CallExceptionResponse(exception.id(), exception.callId(), exception.guardianId(),
                    exception.reason(), exception.grantedBy(), exception.grantedAt(), exception.expiresAt(),
                    exception.active());
        }
    }

    // --- Categorías (coordinador escribe; coordinador y comité leen) ---

    // Sin anotaciones de Bean Validation a propósito: CategoryRules es la única fuente de validación y acumula
    // todos los errores de campo en un solo 422 (Bean Validation cortaría antes con solo los primeros).
    public record CreateCategoryRequest(
            String name,
            Integer birthYearMin,
            Integer birthYearMax,
            Integer capacity,
            UUID nextCategoryId) {
    }

    public record UpdateCategoryRequest(
            String name,
            Integer birthYearMin,
            Integer birthYearMax,
            Integer capacity,
            UUID nextCategoryId,
            Boolean clearNextCategory,
            Boolean active) {
    }

    public record CategoryResponse(UUID id, String name, int birthYearMin, int birthYearMax, int capacity, int occupied,
                                   int available, UUID nextCategoryId, boolean active) {

        static CategoryResponse from(CategoryInfo category) {
            return new CategoryResponse(category.id(), category.name(), category.birthYearMin(),
                    category.birthYearMax(), category.capacity(), category.occupied(), category.available(),
                    category.nextCategoryId(), category.active());
        }
    }

    public record CategorySuggestionResponse(UUID playerId, int birthYear, CategoryResponse suggested) {

        static CategorySuggestionResponse from(CategorySuggestion suggestion) {
            return new CategorySuggestionResponse(suggestion.playerId(), suggestion.birthYear(),
                    suggestion.suggested() == null ? null : CategoryResponse.from(suggestion.suggested()));
        }
    }

    // --- Decisión del comité técnico (solo COMMITTEE escribe; COORDINATOR y COMMITTEE leen) ---
    // Sin anotaciones de Bean Validation: CommitteeRules y el servicio acumulan todos los errores en un solo 422.

    public record AssignCategoryRequest(
            String minuteNumber,
            LocalDate meetingDate,
            List<String> attendees,
            UUID minuteFileId,
            UUID categoryId,
            UUID headCoachId,
            List<UUID> evaluatorIds,
            String capacityExceptionReason) {
    }

    public record PlayerStatusRequest(String reason) {
    }

    public record PlayerStatusResponse(UUID playerId, String previousStatus, String status) {

        public static PlayerStatusResponse from(PlayerStatusChange change) {
            return new PlayerStatusResponse(change.playerId(), change.previousStatus().name(), change.status().name());
        }
    }

    public record RejectEnrollmentRequest(
            String minuteNumber,
            LocalDate meetingDate,
            List<String> attendees,
            UUID minuteFileId,
            String reason) {
    }

    public record AssignmentResponse(UUID playerId, String playerStatus, UUID categoryId, String categoryName,
                                     UUID headCoachId, List<UUID> evaluatorIds, String minuteNumber,
                                     LocalDate meetingDate, UUID minuteFileId, String capacityExceptionReason,
                                     Instant assignedAt) {

        static AssignmentResponse from(AssignmentView view) {
            return new AssignmentResponse(view.playerId(), view.playerStatus().name(), view.categoryId(),
                    view.categoryName(), view.headCoachId(), view.evaluatorIds(), view.minuteNumber(),
                    view.meetingDate(), view.minuteFileId(), view.capacityExceptionReason(), view.assignedAt());
        }
    }

    public record DecisionResponse(UUID playerId, String playerStatus, String minuteNumber) {

        static DecisionResponse from(DecisionResult result) {
            return new DecisionResponse(result.playerId(), result.playerStatus().name(), result.minuteNumber());
        }
    }

    /** Sin Bean Validation: la tarifa y las fechas se validan en el dominio con el resto de errores. */
    public record StartRegistrationRequest(UUID feeId, LocalDate dueDate) {
    }

    public record RegistrationResponse(UUID playerId, String playerStatus, UUID chargeId) {

        static RegistrationResponse from(RegistrationResult result) {
            return new RegistrationResponse(result.playerId(), result.playerStatus().name(), result.chargeId());
        }
    }

    public record MonthlyBillingRequest(UUID feeId, LocalDate period, LocalDate dueDate) {
    }

    public record MonthlyBillingResponse(int players, int created) {

        static MonthlyBillingResponse from(MonthlyBillingResult result) {
            return new MonthlyBillingResponse(result.players(), result.created());
        }
    }
}
