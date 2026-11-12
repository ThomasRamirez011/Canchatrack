package co.edu.unipamplona.canchatrack.scheduling.application;

/**
 * Datos descriptivos de un evento para redactar un aviso: nombre de la categoría, dirección del escenario, nombre del
 * entrenador y, si el evento es un partido ya registrado, el rival.
 */
public record EventContext(String categoryName, String venueAddress, String coachName, String opponent) {

    public EventContext(String categoryName, String venueAddress, String coachName) {
        this(categoryName, venueAddress, coachName, null);
    }
}
