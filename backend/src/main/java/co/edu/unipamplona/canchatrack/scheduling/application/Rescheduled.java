package co.edu.unipamplona.canchatrack.scheduling.application;

/** El evento antes y después de reprogramarlo. */
public record Rescheduled(EventView before, EventView after) {
}
