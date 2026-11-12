package co.edu.unipamplona.canchatrack.players.application;

/** Cobro mensual: a cuántos jugadores activos alcanzó y cuántos cargos se crearon (el resto ya existían). */
public record MonthlyBillingResult(int players, int created) {
}
