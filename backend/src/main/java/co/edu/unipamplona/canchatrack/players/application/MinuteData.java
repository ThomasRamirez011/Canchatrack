package co.edu.unipamplona.canchatrack.players.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Acta de la reunión del comité que respalda la decisión (RN-03, RF-045). {@code minuteFileId} apunta a un archivo ya almacenado. */
public record MinuteData(String minuteNumber, LocalDate meetingDate, List<String> attendees, UUID minuteFileId) {
}
