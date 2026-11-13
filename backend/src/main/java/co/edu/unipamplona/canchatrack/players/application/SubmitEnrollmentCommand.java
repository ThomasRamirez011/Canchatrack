package co.edu.unipamplona.canchatrack.players.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.documents.api.EnrollmentDocuments.Attachment;

/**
 * Datos de la radicación (DAD CU-02). {@code documents} son los documentos iniciales: archivos que el acudiente ya subió,
 * todos opcionales (los obligatorios que falten se informan en la constancia y se cargan después).
 */
public record SubmitEnrollmentCommand(
        UUID callId,
        String firstName,
        String lastName,
        String documentType,
        String documentNumber,
        LocalDate birthDate,
        String relationship,
        boolean acceptDataProcessing,
        boolean acceptParticipation,
        List<Attachment> documents) {

    public SubmitEnrollmentCommand {
        documents = documents == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(documents));
    }

    /** Radicación sin documentos adjuntos. */
    public SubmitEnrollmentCommand(UUID callId, String firstName, String lastName, String documentType,
                                   String documentNumber, LocalDate birthDate, String relationship,
                                   boolean acceptDataProcessing, boolean acceptParticipation) {
        this(callId, firstName, lastName, documentType, documentNumber, birthDate, relationship, acceptDataProcessing,
                acceptParticipation, List.of());
    }
}
