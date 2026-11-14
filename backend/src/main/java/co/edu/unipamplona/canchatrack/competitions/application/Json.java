package co.edu.unipamplona.canchatrack.competitions.application;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Resúmenes JSON para la auditoría y para el detalle de elegibilidad guardado con cada convocado. */
final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    static String of(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            Object value = keysAndValues[i + 1];
            if (value != null) {
                map.put((String) keysAndValues[i], value instanceof Number || value instanceof Boolean ? value : value.toString());
            }
        }
        return write(map);
    }

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }
}
