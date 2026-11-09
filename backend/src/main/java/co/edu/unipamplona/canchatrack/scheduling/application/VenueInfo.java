package co.edu.unipamplona.canchatrack.scheduling.application;

import java.util.UUID;

public record VenueInfo(UUID id, String name, String address, String reference, boolean active) {
}
