package co.edu.unipamplona.canchatrack.reports.application;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Resúmenes JSON para la auditoría (Jackson: un texto con comillas no rompe el documento). Omite los valores nulos. */
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
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }
}
