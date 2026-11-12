package co.edu.unipamplona.canchatrack.scheduling.application;

/** Datos descriptivos de un evento para redactar un aviso: nombre de la categoría, dirección del escenario y nombre del entrenador. */
public record EventContext(String categoryName, String venueAddress, String coachName) {
}
