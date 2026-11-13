package co.edu.unipamplona.canchatrack.identity.domain;

/** Qué debe hacer el cliente después de la contraseña. */
public enum MfaStep {
    /** La cuenta ya tiene segundo factor: debe presentar un código. */
    VERIFY,
    /** La cuenta lo exige pero aún no lo tiene: debe configurarlo antes de entrar. */
    ENROLL
}
