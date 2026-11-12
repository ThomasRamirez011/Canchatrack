package co.edu.unipamplona.canchatrack.documents.api;

import java.util.UUID;

/**
 * Fachada pública: guardar un archivo que el propio sistema genera (p. ej. el PDF del informe de temporada) en el mismo
 * almacén que los archivos que suben los usuarios, con las mismas validaciones de formato, tamaño e integridad. Se une
 * a la transacción de quien llama. Quién puede descargarlo lo decide {@code GET /files/{id}}: quien lo guardó, la
 * coordinación y el comité.
 */
public interface FileArchive {

    /**
     * @param ownerId     usuario que se registra como autor del archivo
     * @param contentType tipo declarado ({@code application/pdf}, …); el contenido se comprueba contra él
     * @throws co.edu.unipamplona.canchatrack.shared.domain.DomainException FILE_REJECTED si el archivo no es admisible
     */
    ArchivedFile store(UUID ownerId, String fileName, String contentType, byte[] content);

    /** Archivo guardado y la huella SHA-256 (hexadecimal en minúsculas) de su contenido. */
    record ArchivedFile(UUID id, String sha256) {
    }
}
