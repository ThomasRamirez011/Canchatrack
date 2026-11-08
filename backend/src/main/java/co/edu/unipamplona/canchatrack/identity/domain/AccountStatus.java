package co.edu.unipamplona.canchatrack.identity.domain;

/** Estado administrativo de la cuenta ({@code users.status}). El bloqueo temporal por intentos va aparte ({@code locked_until}). */
public enum AccountStatus {
    ACTIVE,
    LOCKED,
    DISABLED;

    public static AccountStatus parse(String value) {
        return AccountStatus.valueOf(value);
    }
}
