package co.edu.unipamplona.canchatrack.attendance.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.attendance.application.AttendanceTelemetry;
import co.edu.unipamplona.canchatrack.attendance.application.SyncResult;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;
import io.opentelemetry.api.OpenTelemetry;

/**
 * La telemetría no puede cambiar el resultado ni las excepciones del caso de uso (Guía 13). Con la API de OpenTelemetry
 * en modo no-op, que es lo que hay en ejecución cuando no se adjunta el agente.
 */
class OtelAttendanceTelemetryTest {

    private static final UUID EVENT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final OtelAttendanceTelemetry telemetry = new OtelAttendanceTelemetry(OpenTelemetry.noop());

    @Test
    void returnsTheUseCaseResultUnchanged() {
        SyncResult expected = new SyncResult(false, 3, 2, 1, 0, List.of());

        SyncResult actual = telemetry.traceSync(EVENT, 3, () -> expected);

        assertThat(actual).isSameAs(expected);
    }

    @Test
    void doesNotCountARepeatedBatchTwice() {
        SyncResult replay = new SyncResult(true, 3, 2, 1, 0, List.of());

        assertThat(telemetry.traceSync(EVENT, 3, () -> replay)).isSameAs(replay);
    }

    @Test
    void rethrowsDomainExceptionsWithoutWrappingThem() {
        DomainException expected = new DomainException(ErrorCode.ATTENDANCE_SESSION_CLOSED, null);

        assertThatThrownBy(() -> telemetry.traceSync(EVENT, 1, () -> { throw expected; })).isSameAs(expected);
    }

    @Test
    void rethrowsUnexpectedFailuresWithoutWrappingThem() {
        IllegalStateException expected = new IllegalStateException("fallo de infraestructura");

        assertThatThrownBy(() -> telemetry.traceSync(EVENT, 1, () -> { throw expected; })).isSameAs(expected);
    }

    @Test
    void runsTheUseCaseExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();

        telemetry.traceSync(EVENT, 1, () -> {
            calls.incrementAndGet();
            return new SyncResult(false, 1, 1, 0, 0, List.of());
        });

        assertThat(calls).hasValue(1);
    }

    @Test
    void theNoTelemetryOptionJustRunsTheUseCase() {
        SyncResult expected = new SyncResult(false, 1, 1, 0, 0, List.of());

        assertThat(AttendanceTelemetry.NONE.traceSync(EVENT, 1, () -> expected)).isSameAs(expected);
    }
}
