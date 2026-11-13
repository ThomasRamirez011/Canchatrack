package co.edu.unipamplona.canchatrack.attendance.application;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Puerto de observabilidad del endpoint crítico {@code POST /api/v1/events/{eventId}/attendance} (Guía 13).
 * El caso de uso no conoce OpenTelemetry: la infraestructura decide si hay un span y una métrica o nada ({@link #NONE}).
 *
 * <p>Regla: ningún dato personal en la telemetría. Solo viajan el identificador del evento, el tamaño del lote y
 * conteos por resultado; nunca nombres, documentos ni identificadores de jugadores (son menores de edad).
 */
@FunctionalInterface
public interface AttendanceTelemetry {

    /** Sin telemetría: ejecuta el caso de uso tal cual. */
    AttendanceTelemetry NONE = (eventId, batchSize, useCase) -> useCase.get();

    /** Envuelve la sincronización de un lote sin cambiar su resultado ni sus excepciones. */
    SyncResult traceSync(UUID eventId, int batchSize, Supplier<SyncResult> useCase);
}
