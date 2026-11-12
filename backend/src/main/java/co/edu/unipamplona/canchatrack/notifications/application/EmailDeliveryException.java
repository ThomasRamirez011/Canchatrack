package co.edu.unipamplona.canchatrack.notifications.application;

/**
 * Fallo al entregar un correo al proveedor. {@code permanent} indica que reintentar no sirve (dirección inválida o
 * rechazada); un fallo transitorio (conexión, tiempo de espera) se reintenta según la política.
 */
public class EmailDeliveryException extends Exception {

    private static final long serialVersionUID = 1L;

    private final boolean permanent;

    public EmailDeliveryException(String message, boolean permanent, Throwable cause) {
        super(message, cause);
        this.permanent = permanent;
    }

    public boolean permanent() {
        return permanent;
    }
}
