package co.edu.unipamplona.canchatrack.shared.web;

import org.slf4j.MDC;

/** Acceso al identificador de correlación de la solicitud actual (API, dominio, correo e informe; DDS 12.3). */
public final class CorrelationIds {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationIds() {
    }

    /** @return el correlationId de la solicitud en curso, o {@code null} fuera de una solicitud. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
