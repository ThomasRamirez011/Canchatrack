package co.edu.unipamplona.canchatrack.players.application;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Resúmenes JSON para la auditoría. Serializa con Jackson: un nombre con comillas no puede romper el documento. */
final class AuditJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private AuditJson() {
    }

    /** Pares clave/valor en orden; los valores {@code null} se omiten y todo lo que no sea número o booleano va como texto. */
    static String of(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("keys and values must pair up");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            Object value = keysAndValues[i + 1];
            if (value != null) {
                map.put((String) keysAndValues[i],
                        value instanceof Number || value instanceof Boolean ? value : value.toString());
            }
        }
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JacksonException e) {
            throw new IllegalStateException("audit summary cannot be serialized", e);
        }
    }
}
