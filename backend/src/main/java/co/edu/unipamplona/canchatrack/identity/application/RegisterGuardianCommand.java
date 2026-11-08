package co.edu.unipamplona.canchatrack.identity.application;

/** Datos del autorregistro de un acudiente. La contraseña no aparece en {@code toString}. */
public record RegisterGuardianCommand(
        String email,
        String fullName,
        String password,
        String documentType,
        String documentNumber,
        String phone) {

    @Override
    public String toString() {
        return "RegisterGuardianCommand[email=" + email + ", password=***]";
    }
}
