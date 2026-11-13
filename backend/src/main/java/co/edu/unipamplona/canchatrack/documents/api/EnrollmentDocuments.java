package co.edu.unipamplona.canchatrack.documents.api;

import java.net.InetAddress;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Fachada pública: documentos iniciales que el acudiente adjunta al radicar la inscripción (DAD CU-02, RF-006).
 *
 * <p>Cada adjunto referencia un archivo que el propio acudiente ya subió ({@code POST /files}) y se convierte en la
 * primera versión inmutable del tipo de documento (RF-007, RF-008). La radicación no exige que estén todos los
 * obligatorios: los que falten se devuelven en {@link Attached#missingMandatory()} y se cargan después; la activación
 * del jugador sí los exige (RF-048).
 */
public interface EnrollmentDocuments {

    /** Un documento adjunto: tipo del catálogo, archivo ya subido y datos de la versión. */
    record Attachment(UUID documentTypeId, UUID fileId, LocalDate issuedOn, LocalDate expiresOn, String notes) {
    }

    /** Un campo inválido; {@code field} usa la forma {@code documents[2].fileId}. */
    record Problem(String field, String message) {
    }

    /** @param missingMandatory nombres de los tipos obligatorios que el jugador aún no tiene vigentes */
    record Attached(int count, List<String> missingMandatory) {

        public Attached {
            missingMandatory = missingMandatory == null ? List.of() : List.copyOf(missingMandatory);
        }
    }

    /**
     * Valida los adjuntos sin necesitar al jugador (tipo activo y sin repetir, archivo propio, sin uso previo, tamaño y
     * formato del tipo, vigencia). Devuelve todos los problemas juntos, o una lista vacía.
     */
    List<Problem> check(UUID guardianUserId, List<Attachment> attachments);

    /**
     * Crea las versiones del jugador. Se une a la transacción de quien llama: si algo falla, no queda ninguna. Con una
     * lista vacía no crea nada y solo informa los obligatorios pendientes.
     *
     * @throws co.edu.unipamplona.canchatrack.shared.domain.DomainException VALIDATION_FAILED si cambió algo desde
     *         {@link #check}, o DOCUMENT_UPLOAD_NOT_ALLOWED si el jugador ya tiene una versión de ese tipo y no hay ventana
     *         habilitada ni vencimiento cercano (RN-05)
     */
    Attached attach(UUID guardianUserId, UUID playerId, List<Attachment> attachments, InetAddress ip, String userAgent);
}
