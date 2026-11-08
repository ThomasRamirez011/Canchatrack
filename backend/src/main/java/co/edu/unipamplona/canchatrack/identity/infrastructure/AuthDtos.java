package co.edu.unipamplona.canchatrack.identity.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.identity.application.TokenPair;
import co.edu.unipamplona.canchatrack.identity.application.UserView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** Contratos JSON de autenticación y usuarios (ver openapi/canchatrack-v1.yaml). */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank(message = "El correo es obligatorio.") @Size(max = 254, message = "El correo es demasiado largo.") String email,
            @NotBlank(message = "La contraseña es obligatoria.") @Size(max = 128, message = "La contraseña es demasiado larga.") String password) {

        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=***]";
        }
    }

    public record RefreshRequest(
            @NotBlank(message = "El refresh token es obligatorio.") @Size(max = 256, message = "El refresh token es demasiado largo.") String refreshToken) {

        @Override
        public String toString() {
            return "RefreshRequest[refreshToken=***]";
        }
    }

    public record CreateUserRequest(
            @NotBlank(message = "El correo es obligatorio.") @Size(max = 254, message = "El correo es demasiado largo.") String email,
            @NotBlank(message = "El nombre es obligatorio.") @Size(max = 200, message = "El nombre es demasiado largo.") String fullName,
            @NotBlank(message = "La contraseña es obligatoria.") @Size(max = 128, message = "La contraseña es demasiado larga.") String password,
            @NotEmpty(message = "Debe indicar al menos un rol.") @Size(max = 10, message = "Demasiados roles.") List<@NotBlank String> roles) {

        @Override
        public String toString() {
            return "CreateUserRequest[email=" + email + ", fullName=" + fullName + ", password=***, roles=" + roles + "]";
        }
    }

    public record UserResponse(UUID id, String email, String fullName, List<String> roles) {

        public static UserResponse from(UserView view) {
            return new UserResponse(view.id(), view.email(), view.fullName(), view.roles());
        }
    }

    public record SessionResponse(
            String accessToken,
            String tokenType,
            long expiresIn,
            String refreshToken,
            Instant refreshTokenExpiresAt,
            UserResponse user) {

        public static SessionResponse from(TokenPair tokens, UserView user, Instant now) {
            long expiresIn = Math.max(0, tokens.accessExpiresAt().getEpochSecond() - now.getEpochSecond());
            return new SessionResponse(tokens.accessToken(), "Bearer", expiresIn, tokens.refreshToken(),
                    tokens.refreshExpiresAt(), UserResponse.from(user));
        }

        @Override
        public String toString() {
            return "SessionResponse[user=" + user + "]";
        }
    }
}
