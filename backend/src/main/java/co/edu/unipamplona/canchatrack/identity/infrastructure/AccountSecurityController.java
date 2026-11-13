package co.edu.unipamplona.canchatrack.identity.infrastructure;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.identity.application.ClientOrigin;
import co.edu.unipamplona.canchatrack.identity.application.EmailVerificationService;
import co.edu.unipamplona.canchatrack.identity.application.PasswordRecoveryService;
import co.edu.unipamplona.canchatrack.identity.application.PasswordRecoveryService.ChangeResult;
import co.edu.unipamplona.canchatrack.identity.application.RateLimiter;
import co.edu.unipamplona.canchatrack.identity.domain.RateLimitBucket;
import co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos.ChangePasswordRequest;
import co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos.EmailRequest;
import co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos.ResetPasswordRequest;
import co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos.TokenRequest;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;
import co.edu.unipamplona.canchatrack.shared.security.CurrentUser;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Recuperación y cambio de contraseña, y confirmación del correo. Las solicitudes de correo responden siempre 202 sin
 * decir si la cuenta existe; los canjes devuelven el mismo error para cualquier enlace inservible. Todos los puntos
 * públicos pasan por el límite de tasa antes de tocar la base.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/auth")
public class AccountSecurityController {

    private final PasswordRecoveryService recovery;
    private final EmailVerificationService verification;
    private final RateLimiter rateLimiter;

    public AccountSecurityController(PasswordRecoveryService recovery, EmailVerificationService verification,
                                     RateLimiter rateLimiter) {
        this.recovery = recovery;
        this.verification = verification;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestPasswordReset(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        ClientOrigin origin = ClientOrigins.from(http);
        rateLimiter.checkIp(RateLimitBucket.RECOVERY_IP, origin);
        rateLimiter.checkEmail(RateLimitBucket.RECOVERY_EMAIL, request.email());
        recovery.requestReset(request.email(), origin);
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmPasswordReset(@Valid @RequestBody ResetPasswordRequest request, HttpServletRequest http) {
        ClientOrigin origin = ClientOrigins.from(http);
        rateLimiter.checkIp(RateLimitBucket.CONFIRM_IP, origin);
        recovery.confirmReset(request.token(), request.newPassword(), origin);
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request, HttpServletRequest http) {
        ChangeResult result = recovery.changePassword(CurrentUser.require().id(), request.currentPassword(),
                request.newPassword(), ClientOrigins.from(http));
        switch (result) {
            case ChangeResult.Changed changed -> {
                // 204
            }
            case ChangeResult.WrongCurrentPassword wrong -> throw new DomainException(ErrorCode.VALIDATION_FAILED, null,
                    Map.of("errors", List.of(
                            Map.of("field", "currentPassword", "message", "La contraseña actual no es correcta."))));
            case ChangeResult.Locked locked -> throw RateLimiter.tooMany(locked.retryAfterSeconds());
        }
    }

    @PostMapping("/email-verification/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestEmailVerification(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        ClientOrigin origin = ClientOrigins.from(http);
        rateLimiter.checkIp(RateLimitBucket.RECOVERY_IP, origin);
        rateLimiter.checkEmail(RateLimitBucket.RECOVERY_EMAIL, request.email());
        verification.resend(request.email(), origin);
    }

    @PostMapping("/email-verification/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmEmail(@Valid @RequestBody TokenRequest request, HttpServletRequest http) {
        ClientOrigin origin = ClientOrigins.from(http);
        rateLimiter.checkIp(RateLimitBucket.CONFIRM_IP, origin);
        verification.confirm(request.token(), origin);
    }
}
