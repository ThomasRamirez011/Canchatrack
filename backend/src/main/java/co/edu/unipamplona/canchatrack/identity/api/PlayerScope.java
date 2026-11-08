package co.edu.unipamplona.canchatrack.identity.api;

/** Alcance de consulta de jugadores que otorgan los roles de un usuario (para listados, donde no hay un id concreto). */
public enum PlayerScope {

    /** Todos los jugadores (coordinador, comité técnico). */
    ALL,

    /** Solo los jugadores con vínculo vigente con el acudiente. */
    LINKED_GUARDIAN,

    /** Ninguno. */
    NONE
}
