package co.edu.unipamplona.canchatrack.audit.api;

/**
 * Puerto de escritura de la bitácora inmutable (ISP, DDS 2.1). Es la fachada pública del módulo {@code audit}
 * que usan los demás módulos.
 *
 * <p>Toda operación sensible (DDS 12.2, RN-19) registra su evidencia mediante este puerto.
 */
public interface AuditWriter {

    /**
     * Registra el evento <b>dentro de la transacción de negocio en curso</b> (DDS 3.1 y 7.2): si la operación se
     * revierte, la evidencia se revierte con ella. Falla si no hay transacción activa.
     */
    void write(AuditRecord record);

    /**
     * Registra el evento en una transacción <b>independiente</b>, para intentos que deben dejar rastro aunque la
     * operación de negocio se rechace o revierta (por ejemplo inscripción fuera de convocatoria, PA-01, o
     * acceso denegado).
     */
    void writeIndependent(AuditRecord record);
}
