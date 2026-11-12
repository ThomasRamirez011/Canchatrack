package co.edu.unipamplona.canchatrack.identity.api;

/**
 * Clase de dato de un jugador que se quiere consultar. Permite dar al personal deportivo solo lo que necesita sin
 * abrirle el resto (pagos, documentos, contacto del acudiente): {@link PlayerAccessPolicy#canAccess} sigue siendo el
 * acceso completo y {@link PlayerAccessPolicy#canView} el acceso por clase de dato.
 */
public enum PlayerData {

    /** Nombre, edad, categoría y estado. Sin documento de identidad, inscripciones ni datos del acudiente. */
    BASIC,

    /** Asistencia acumulada del jugador. */
    ATTENDANCE,

    /** Historial de partidos del jugador. */
    MATCHES
}
