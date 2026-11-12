package co.edu.unipamplona.canchatrack.evaluations.domain;

/** Resultado de la evaluación determinista de unanimidad (DDS 5.1). */
public enum RoundOutcome {

    /** Menos de tres conceptos vigentes: no se habilita el ascenso. */
    PENDING,

    /** Hay al menos un concepto no favorable o con seguimiento (con tres emitidos). */
    FOLLOW_UP,

    /** Tres conceptos favorables sobre el mismo periodo (RN-09). */
    APPROVED
}
