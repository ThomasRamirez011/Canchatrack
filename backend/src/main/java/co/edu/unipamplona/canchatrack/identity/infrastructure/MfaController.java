package co.edu.unipamplona.canchatrack.identity.infrastructure;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.identity.application.ClientOrigin;
import co.edu.unipamplona.canchatrack.identity.application.MfaAuthenticationService;
import co.edu.unipamplona.canchatrack.identity.application.MfaAuthenticationService.MfaOutcome;
import co.edu.unipamplona.canchatrack.identity.application.MfaAuthenticationService.RegenerateResult;
import co.edu.unipamplona.canchatrack.identity.application.RateLimiter;
import co.edu.unipamplona.canchatrack.identity.domain.RateLimitBucket;
import co.edu.unipamplona.canchatrack.identity.domain.RoleCode;
import co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos.BackupCodesResponse;
import co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos.MfaChallengeRequest;
import co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos.MfaCodeRequest;
import co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos.MfaEnrolledResponse;
import co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos.MfaEnrollmentResponse;
import co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos.MfaStatusResponse;
import co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos.MfaVerifyRequest;
import co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos.SessionResponse;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;
import co.edu.unipamplona.canchatrack.shared.security.AuthenticatedUser;
import co.edu.unipamplona.canchatrack.shared.security.CurrentUser;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Segundo factor (TOTP). Los tres primeros puntos son públicos porque se usan antes de tener sesión: se identifican con
 * el desafío que devolvió el inicio de sesión. Todos pasan por el límite de tasa y responden el mismo 401 para
 * cualquier desafío o código inservible.
 */
@RestController
@RequestMapping(ApiPaths.V1)
public class MfaController {

    private static final String INVALID_CHALLENGE = "El código o el desafío no son válidos. Inicie sesión de nuevo.";

    private final MfaAuthenticationService mfa;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    public MfaController(MfaAuthenticationService mfa, RateLimiter rateLimiter, Clock clock) {
        this.mfa = mfa;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /** Entrega el secreto para la aplicación autenticadora (cuenta que debe configurar el factor). */
    @PostMapping("/auth/mfa/enrollment")
    public ResponseEntity<MfaEnrollmentResponse> beginEnrollment(@Valid @RequestBody MfaChallengeRequest request,
                                                                 HttpServletRequest http) {
        ClientOrigin origin = ClientOrigins.from(http);
        rateLimiter.checkIp(RateLimitBucket.CONFIRM_IP, origin);
        return mfa.beginEnrollment(request.challengeToken(), origin)
                .map(secret -> ResponseEntity.ok()
                        .header("Cache-Control", "no-store")
                        .body(new MfaEnrollmentResponse(secret.secret(), secret.otpauthUri(), secret.issuer(),
                                secret.accountName())))
                .orElseThrow(() -> new DomainException(ErrorCode.AUTH_UNAUTHENTICATED, INVALID_CHALLENGE));
    }

    /** Confirma la configuración con el primer código, abre la sesión y entrega los códigos de respaldo (una vez). */
    @PostMapping("/auth/mfa/enrollment/confirm")
    public ResponseEntity<MfaEnrolledResponse> confirmEnrollment(@Valid @RequestBody MfaVerifyRequest request,
                                                                 HttpServletRequest http) {
        ClientOrigin origin = ClientOrigins.from(http);
        rateLimiter.checkIp(RateLimitBucket.CONFIRM_IP, origin);
        MfaOutcome outcome = mfa.confirmEnrollment(request.challengeToken(), request.code(), origin);
        return switch (outcome) {
            case MfaOutcome.Opened opened -> ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .body(new MfaEnrolledResponse(
                            SessionResponse.from(opened.session().tokens(), opened.session().user(), clock.instant()),
                            opened.backupCodes()));
            case MfaOutcome.Rejected rejected ->
                    throw new DomainException(ErrorCode.AUTH_UNAUTHENTICATED, INVALID_CHALLENGE);
        };
    }

    /** Completa el inicio de sesión con el código de la aplicación o un código de respaldo. */
    @PostMapping("/auth/mfa/verify")
    public ResponseEntity<SessionResponse> verify(@Valid @RequestBody MfaVerifyRequest request, HttpServletRequest http) {
        ClientOrigin origin = ClientOrigins.from(http);
        rateLimiter.checkIp(RateLimitBucket.CONFIRM_IP, origin);
        MfaOutcome outcome = mfa.verify(request.challengeToken(), request.code(), origin);
        return switch (outcome) {
            case MfaOutcome.Opened opened -> ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .body(SessionResponse.from(opened.session().tokens(), opened.session().user(), clock.instant()));
            case MfaOutcome.Rejected rejected ->
                    throw new DomainException(ErrorCode.AUTH_UNAUTHENTICATED, INVALID_CHALLENGE);
        };
    }

    @GetMapping("/auth/mfa")
    public MfaStatusResponse status() {
        MfaAuthenticationService.Status status = mfa.status(CurrentUser.require().id());
        return new MfaStatusResponse(status.required(), status.enrolled(), status.backupCodesRemaining());
    }

    /** Cambia los códigos de respaldo por otros nuevos; exige un código vigente de la aplicación. */
    @PostMapping("/auth/mfa/backup-codes")
    public ResponseEntity<BackupCodesResponse> regenerateBackupCodes(@Valid @RequestBody MfaCodeRequest request,
                                                                     HttpServletRequest http) {
        RegenerateResult result = mfa.regenerateBackupCodes(CurrentUser.require().id(), request.code(),
                ClientOrigins.from(http));
        return switch (result) {
            case RegenerateResult.Regenerated regenerated -> ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .body(new BackupCodesResponse(regenerated.backupCodes()));
            case RegenerateResult.InvalidCode invalid -> throw new DomainException(ErrorCode.VALIDATION_FAILED, null,
                    Map.of("errors", List.of(Map.of("field", "code", "message", "El código no es correcto."))));
            case RegenerateResult.Locked locked -> throw RateLimiter.tooMany(locked.retryAfterSeconds());
            case RegenerateResult.NotEnrolled notEnrolled -> throw new DomainException(
                    ErrorCode.RESOURCE_STATE_CONFLICT, "Su cuenta no tiene un segundo factor configurado.");
        };
    }

    /** Un coordinador reinicia el segundo factor de otra persona (perdió el teléfono y los códigos). */
    @PostMapping("/users/{userId}/mfa/reset")
    @PreAuthorize("hasRole('COORDINATOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@PathVariable UUID userId, HttpServletRequest http) {
        AuthenticatedUser actor = CurrentUser.require();
        mfa.reset(userId, actor.id(), RoleCode.COORDINATOR.name(), ClientOrigins.from(http));
    }
}
