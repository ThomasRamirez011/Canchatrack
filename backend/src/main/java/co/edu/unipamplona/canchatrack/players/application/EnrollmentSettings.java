package co.edu.unipamplona.canchatrack.players.application;

/** @param consentVersion identificador de la versión vigente del texto de consentimiento que se acepta al radicar */
public record EnrollmentSettings(String consentVersion) {

    public EnrollmentSettings {
        if (consentVersion == null || consentVersion.isBlank() || consentVersion.length() > 50) {
            throw new IllegalArgumentException("consentVersion is required (max 50 characters)");
        }
    }
}
