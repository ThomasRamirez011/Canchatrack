package co.edu.unipamplona.canchatrack.players.application;

import java.time.LocalDate;
import java.util.UUID;

/** Datos de la radicación (DAD CU-02). Los documentos adjuntos llegarán con el módulo documents. */
public record SubmitEnrollmentCommand(
        UUID callId,
        String firstName,
        String lastName,
        String documentType,
        String documentNumber,
        LocalDate birthDate,
        String relationship,
        boolean acceptDataProcessing,
        boolean acceptParticipation) {
}
