package co.edu.unipamplona.canchatrack.players.api;

import java.util.UUID;

public record RosterPlayer(UUID id, String code, String firstName, String lastName) {
}
