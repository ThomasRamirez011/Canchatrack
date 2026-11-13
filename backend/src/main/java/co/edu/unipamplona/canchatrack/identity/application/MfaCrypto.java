package co.edu.unipamplona.canchatrack.identity.application;

import java.util.UUID;

/** Puerto de salida: cifrado del secreto TOTP y huella de los códigos de respaldo. */
public interface MfaCrypto {

    /** Cifra el secreto ligándolo a su dueño: copiado a la fila de otro usuario no se puede abrir. */
    byte[] seal(UUID owner, byte[] secret);

    byte[] open(UUID owner, byte[] sealed);

    /** Huella con llave (HMAC) de un código de respaldo ya normalizado; sin la llave no se puede reconstruir. */
    String fingerprint(UUID owner, String normalizedCode);
}
