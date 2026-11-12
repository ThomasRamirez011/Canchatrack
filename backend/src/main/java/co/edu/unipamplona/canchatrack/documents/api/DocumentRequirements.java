package co.edu.unipamplona.canchatrack.documents.api;

import java.util.List;
import java.util.UUID;

/**
 * Fachada pública: documentos obligatorios que le faltan a un jugador (RF-048, CA-02). {@code players} la consulta antes de
 * activar al jugador; {@code documents} decide qué es «completo» (obligatorio, activo, con versión y, si vence, vigente).
 * Devuelve solo los nombres de los documentos pendientes: nunca contenido ni datos de salud.
 */
public interface DocumentRequirements {

    /** Nombres de los documentos obligatorios sin versión vigente hoy (en la zona del club); vacío si está completo. */
    List<String> missingMandatory(UUID playerId);
}
