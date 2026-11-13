package co.edu.unipamplona.canchatrack.identity.infrastructure;

import java.time.Clock;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.identity.application.AuthenticationService;
import co.edu.unipamplona.canchatrack.identity.application.RateLimiter;
import co.edu.unipamplona.canchatrack.identity.application.ClientOrigin;
import co.edu.unipamplona.canchatrack.identity.application.SessionResult;
import co.edu.unipamplona.canchatrack.identity.application.UserAccountRepository;
import co.edu.unipamplona.canchatrack.identity.application.UserView;
import co.edu.unipamplona.canchatrack.identity.domain.AccountStatus;
import co.edu.unipamplona.canchatrack.identity.domain.RateLimitBucket;
import co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos.LoginRequest;
import co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos.RefreshRequest;
import co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos.SessionResponse;
import co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos.UserResponse;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;
import co.edu.unipamplona.canchatrack.shared.security.AuthenticatedUser;
import co.edu.unipamplona.canchatrack.shared.security.CurrentUser;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping(ApiPaths.V1 + "/auth")
public class AuthController {

    /** Mismo mensaje para toda causa de rechazo: no se distingue correo inexistente, clave errónea ni bloqueo. */
    private static final String INVALID_CREDENTIALS = "Credenciales inválidas.";
    private static final String INVALID_REFRESH_TOKEN = "El refresh token no es válido o ya expiró. Inicie sesión de nuevo.";

    private final AuthenticationService authentication;
    private final UserAccountRepository users;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    public AuthController(AuthenticationService authentication, UserAccountRepository users, RateLimiter rateLimiter,
                          Clock clock) {
        this.authentication = authentication;
        this.users = users;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    @PostMapping("/login")
    public ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        ClientOrigin origin = ClientOrigins.from(http);
        rateLimiter.checkIp(RateLimitBucket.LOGIN_IP, origin);
        SessionResult result = authentication.login(request.email(), request.password(), origin);
        return respond(result, INVALID_CREDENTIALS);
    }

    @PostMapping("/refresh")
    public ResponseEntity<SessionResponse> refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        SessionResult result = authentication.refresh(request.refreshToken(), ClientOrigins.from(http));
        return respond(result, INVALID_REFRESH_TOKEN);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        authentication.logout(request.refreshToken(), ClientOrigins.from(http));
    }

    @GetMapping("/me")
    public UserResponse me() {
        AuthenticatedUser current = CurrentUser.require();
        // Se lee de la base (no del token) para reflejar el estado actual; una cuenta inexistente o no activa cae en 401.
        UserView view = users.findById(current.id())
                .filter(account -> account.status() == AccountStatus.ACTIVE)
                .map(UserView::from)
                .orElseThrow(() -> new DomainException(ErrorCode.AUTH_UNAUTHENTICATED, null));
        return UserResponse.from(view);
    }

    private ResponseEntity<SessionResponse> respond(SessionResult result, String rejectionDetail) {
        return switch (result) {
            case SessionResult.Opened opened -> ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .body(SessionResponse.from(opened.tokens(), opened.user(), clock.instant()));
            case SessionResult.Rejected rejected ->
                    throw new DomainException(ErrorCode.AUTH_UNAUTHENTICATED, rejectionDetail);
            case SessionResult.EmailNotVerified unverified ->
                    throw new DomainException(ErrorCode.EMAIL_NOT_VERIFIED, null);
        };
    }
}
