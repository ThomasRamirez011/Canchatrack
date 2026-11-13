package co.edu.unipamplona.canchatrack.scheduling.application;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** La regla con la que se generó una serie de sesiones (RF-014). */
public record SeriesInfo(UUID id, String eventType, UUID categoryId, UUID venueId, UUID headCoachId, List<DayOfWeek> weekdays,
                         LocalDate startDate, LocalDate endDate, LocalTime startTime, LocalTime endTime, String timeZone,
                         String meetingPoint, String instructions, int occurrenceCount) {
}
