package co.edu.unipamplona.canchatrack.players.application;

import java.util.List;

public record PlayerWithEnrollments(PlayerDetail player, List<EnrollmentSummary> enrollments) {

    public PlayerWithEnrollments {
        enrollments = List.copyOf(enrollments);
    }
}
