package co.edu.unipamplona.canchatrack.notifications.application;

import co.edu.unipamplona.canchatrack.notifications.domain.OutboundEmail;

/** Puerto de salida hacia el proveedor de correo (SMTP, API de un tercero o el remitente simulado de desarrollo). */
public interface EmailSender {

    /** Entrega el correo y devuelve una referencia breve del proveedor (sin datos personales) para la evidencia de entrega. */
    String send(OutboundEmail email) throws EmailDeliveryException;
}
