package co.edu.unipamplona.canchatrack.scheduling.application;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Serie semanal: los días indicados (MONDAY..SUNDAY), a la misma hora local del club, entre {@code startDate} y {@code endDate} (incluidas). */
public record ScheduleSeriesCommand(String eventType, UUID categoryId, UUID venueId, UUID headCoachId, List<String> weekdays,
                                    LocalDate startDate, LocalDate endDate, LocalTime startTime, LocalTime endTime,
                                    String meetingPoint, String instructions) {
}
