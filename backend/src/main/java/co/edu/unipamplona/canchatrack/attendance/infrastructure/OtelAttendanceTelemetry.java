package co.edu.unipamplona.canchatrack.attendance.infrastructure;

import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import co.edu.unipamplona.canchatrack.attendance.application.AttendanceTelemetry;
import co.edu.unipamplona.canchatrack.attendance.application.SyncResult;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

/**
 * Adaptador OpenTelemetry del puerto {@link AttendanceTelemetry}: un span {@code attendance.sync_batch} y un contador
 * {@code canchatrack.attendance.records} por resultado. Usa la API global: con el agente Java de OpenTelemetry adjunto
 * exporta al colector; sin agente la API es un no-op y el costo es despreciable.
 */
@Component
class OtelAttendanceTelemetry implements AttendanceTelemetry {

    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");
    private static final String SCOPE = "canchatrack.attendance";

    private final Tracer tracer;
    private final LongCounter records;

    @Autowired
    OtelAttendanceTelemetry() {
        this(GlobalOpenTelemetry.get());
    }

    OtelAttendanceTelemetry(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer(SCOPE);
        this.records = openTelemetry.getMeter(SCOPE)
                .counterBuilder("canchatrack.attendance.records")
                .setDescription("Marcas de asistencia recibidas, por resultado")
                .setUnit("{record}")
                .build();
    }

    @Override
    public SyncResult traceSync(UUID eventId, int batchSize, Supplier<SyncResult> useCase) {
        Span span = tracer.spanBuilder("attendance.sync_batch").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            span.setAttribute("event.id", eventId.toString());
            span.setAttribute("batch.size", batchSize);
            SyncResult result = useCase.get();
            span.setAttribute("batch.replayed", result.replayed());
            if (!result.replayed()) { // un reintento devuelve los conteos del lote original: no se cuentan dos veces
                records.add(result.accepted(), Attributes.of(OUTCOME, "accepted"));
                records.add(result.duplicates(), Attributes.of(OUTCOME, "duplicated"));
                records.add(result.rejected(), Attributes.of(OUTCOME, "rejected"));
            }
            return result;
        } catch (RuntimeException ex) {
            span.recordException(ex);
            if (!(ex instanceof DomainException)) { // un 4xx esperado no es un fallo del sistema
                span.setStatus(StatusCode.ERROR);
            }
            throw ex;
        } finally {
            span.end();
        }
    }
}
