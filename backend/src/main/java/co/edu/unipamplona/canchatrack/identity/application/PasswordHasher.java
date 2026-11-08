package co.edu.unipamplona.canchatrack.identity.application;

/** Puerto de salida: hash y verificación de contraseñas. */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String hash);

    /**
     * Gasta el mismo tiempo que una verificación real. Se invoca cuando no hay hash contra el que comparar (correo
     * desconocido, cuenta sin contraseña) para que el tiempo de respuesta no revele si el correo existe.
     */
    void simulateVerification(String rawPassword);
}
