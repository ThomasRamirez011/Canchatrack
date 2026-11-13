package co.edu.unipamplona.canchatrack.scheduling.application;

import java.util.List;

/** Una serie con todas sus sesiones, también las canceladas, en orden cronológico. */
public record SeriesView(SeriesInfo series, List<EventView> events) {
}
