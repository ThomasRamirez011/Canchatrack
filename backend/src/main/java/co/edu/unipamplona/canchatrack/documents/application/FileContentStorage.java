package co.edu.unipamplona.canchatrack.documents.application;

/** Puerto de salida: contenido de los archivos (disco en desarrollo; un almacén de objetos en producción). */
public interface FileContentStorage {

    void write(String key, byte[] content);

    byte[] read(String key);
}
