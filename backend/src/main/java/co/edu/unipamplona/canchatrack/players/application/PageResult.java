package co.edu.unipamplona.canchatrack.players.application;

import java.util.List;

/** Página de resultados; {@code page} empieza en 0. */
public record PageResult<T>(List<T> items, int page, int size, long total) {

    public PageResult {
        items = List.copyOf(items);
    }
}
