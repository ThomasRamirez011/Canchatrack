package co.edu.unipamplona.canchatrack.documents.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * Reglas puras de documentos y vigencias (RF-006 a RF-010, RN-05, RN-06). Sin framework: reciben fechas ya resueltas por
 * quien llama, de modo que la vigencia es testeable sin reloj.
 *
 * <p>Supuestos documentados (D-04 sigue abierta: el club aún no entrega la lista de documentos que exige):
 * <ul>
 *   <li>«Ante vencimiento» (RF-006) significa documento vencido o a 30 días o menos de vencer, que es cuando empieza la alerta.</li>
 *   <li>La carga inicial (sin versión previa) siempre se permite al acudiente: es el expediente que se está armando.</li>
 *   <li>Un documento que exige vigencia se rechaza si ya llega vencido: no serviría para nada.</li>
 * </ul>
 */
public final class DocumentRules {

    public static final int MAX_CODE = 40;
    public static final int MAX_NAME = 120;
    public static final int MAX_NOTES = 500;
    public static final int MAX_REASON = 500;
    public static final int MAX_VALIDITY_DAYS = 3650;
    /** Primera y segunda alerta de vencimiento (RF-010). */
    public static final int FIRST_ALERT_DAYS = 30;
    public static final int SECOND_ALERT_DAYS = 7;

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,39}");

    /** Estado de un documento de un jugador frente a la fecha de consulta. */
    public enum Status {
        /** Aún no hay ninguna versión. */
        MISSING,
        /** Hay versión y no vence (o vence después de la ventana de alerta). */
        VALID,
        /** Vence en 30 días o menos (primera alerta de RF-010). */
        EXPIRING_SOON,
        /** Su fecha de vencimiento ya pasó. */
        EXPIRED
    }

    private DocumentRules() {
    }

    public static List<FieldError> codeViolations(String code) {
        List<FieldError> errors = new ArrayList<>();
        if (code == null || code.isBlank()) {
            errors.add(new FieldError("code", "El código es obligatorio."));
        } else if (!CODE.matcher(code.trim()).matches()) {
            errors.add(new FieldError("code", "El código usa mayúsculas, dígitos y guion bajo (2 a " + MAX_CODE
                    + " caracteres) y empieza por una letra."));
        }
        return errors;
    }

    /** Valida los datos configurables de un tipo de documento; el tamaño y los formatos se limitan a lo que el sistema acepta. */
    public static List<FieldError> typeViolations(String name, boolean requiresExpiry, Integer validityDays,
                                                  Long maxSizeBytes, Collection<String> allowedContentTypes,
                                                  Collection<String> supportedContentTypes, long systemMaxSizeBytes) {
        List<FieldError> errors = new ArrayList<>();
        if (name == null || name.isBlank()) {
            errors.add(new FieldError("name", "El nombre es obligatorio."));
        } else if (name.trim().length() > MAX_NAME) {
            errors.add(new FieldError("name", "El nombre admite hasta " + MAX_NAME + " caracteres."));
        }
        if (validityDays != null) {
            if (!requiresExpiry) {
                errors.add(new FieldError("validityDays", "La vigencia en días solo aplica a documentos que vencen."));
            } else if (validityDays < 1 || validityDays > MAX_VALIDITY_DAYS) {
                errors.add(new FieldError("validityDays", "La vigencia debe estar entre 1 y " + MAX_VALIDITY_DAYS + " días."));
            }
        }
        if (maxSizeBytes != null && (maxSizeBytes < 1 || maxSizeBytes > systemMaxSizeBytes)) {
            errors.add(new FieldError("maxSizeBytes", "El tamaño máximo debe estar entre 1 y " + systemMaxSizeBytes + " bytes."));
        }
        if (allowedContentTypes != null) {
            if (allowedContentTypes.isEmpty()) {
                errors.add(new FieldError("allowedContentTypes", "Indique al menos un formato o deje el campo vacío para admitir todos."));
            } else if (!supportedContentTypes.containsAll(allowedContentTypes)) {
                errors.add(new FieldError("allowedContentTypes", "Formatos admitidos por el sistema: "
                        + String.join(", ", supportedContentTypes) + "."));
            }
        }
        return errors;
    }

    /** Ventana habilitada para cargar (RN-05): rango coherente, no terminada y motivo acotado. */
    public static List<FieldError> windowViolations(Instant opensAt, Instant closesAt, String reason, Instant now) {
        List<FieldError> errors = new ArrayList<>();
        if (opensAt == null) {
            errors.add(new FieldError("opensAt", "La fecha de apertura es obligatoria."));
        }
        if (closesAt == null) {
            errors.add(new FieldError("closesAt", "La fecha de cierre es obligatoria."));
        }
        if (opensAt != null && closesAt != null) {
            if (!opensAt.isBefore(closesAt)) {
                errors.add(new FieldError("closesAt", "El cierre debe ser posterior a la apertura."));
            } else if (!closesAt.isAfter(now)) {
                errors.add(new FieldError("closesAt", "La ventana ya terminó; indique un cierre futuro."));
            }
        }
        if (reason != null && reason.trim().length() > MAX_REASON) {
            errors.add(new FieldError("reason", "El motivo admite hasta " + MAX_REASON + " caracteres."));
        }
        return errors;
    }

    /**
     * Fecha de vencimiento efectiva: la indicada o, si el tipo define una vigencia en días, la de emisión más esos días.
     * Un tipo que no vence conserva la fecha solo si el usuario la indicó.
     */
    public static LocalDate resolveExpiry(boolean requiresExpiry, Integer validityDays, LocalDate issuedOn, LocalDate expiresOn) {
        if (expiresOn != null) {
            return expiresOn;
        }
        if (requiresExpiry && validityDays != null && issuedOn != null) {
            return issuedOn.plusDays(validityDays);
        }
        return null;
    }

    public static List<FieldError> versionViolations(boolean requiresExpiry, LocalDate issuedOn, LocalDate resolvedExpiry,
                                                     String notes, LocalDate today) {
        List<FieldError> errors = new ArrayList<>();
        if (issuedOn != null && issuedOn.isAfter(today)) {
            errors.add(new FieldError("issuedOn", "La fecha de emisión no puede ser futura."));
        }
        if (requiresExpiry && resolvedExpiry == null) {
            errors.add(new FieldError("expiresOn", "Este documento vence: indique la fecha de vencimiento."));
        }
        if (resolvedExpiry != null) {
            if (issuedOn != null && resolvedExpiry.isBefore(issuedOn)) {
                errors.add(new FieldError("expiresOn", "El vencimiento no puede ser anterior a la emisión."));
            } else if (requiresExpiry && resolvedExpiry.isBefore(today)) {
                errors.add(new FieldError("expiresOn", "El documento ya está vencido; cargue uno vigente."));
            }
        }
        if (notes != null && notes.trim().length() > MAX_NOTES) {
            errors.add(new FieldError("notes", "La observación admite hasta " + MAX_NOTES + " caracteres."));
        }
        return errors;
    }

    /**
     * Tamaño y formato de un archivo frente a su tipo de documento. El límite es el menor entre el del tipo y el del
     * sistema; {@code allowedContentTypes} vacío admite los formatos generales del sistema.
     */
    public static List<FieldError> fileViolations(String field, long sizeBytes, String contentType, Long typeMaxBytes,
                                                  long systemMaxBytes, Collection<String> allowedContentTypes) {
        List<FieldError> errors = new ArrayList<>();
        long limit = typeMaxBytes == null ? systemMaxBytes : Math.min(typeMaxBytes, systemMaxBytes);
        if (sizeBytes > limit) {
            errors.add(new FieldError(field, "El archivo supera el tamaño máximo de este documento (" + limit + " bytes)."));
        }
        if (!allowedContentTypes.isEmpty() && !allowedContentTypes.contains(FileRules.normalizeContentType(contentType))) {
            errors.add(new FieldError(field, "Formato no admitido para este documento; use "
                    + String.join(", ", allowedContentTypes) + "."));
        }
        return errors;
    }

    /** Estado frente a {@code today}; {@code present=false} es que no hay versión. Sin fecha de vencimiento no vence. */
    public static Status statusOf(boolean present, LocalDate expiresOn, LocalDate today) {
        if (!present) {
            return Status.MISSING;
        }
        if (expiresOn == null) {
            return Status.VALID;
        }
        long days = ChronoUnit.DAYS.between(today, expiresOn);
        if (days < 0) {
            return Status.EXPIRED;
        }
        return days <= FIRST_ALERT_DAYS ? Status.EXPIRING_SOON : Status.VALID;
    }

    /**
     * Alerta de vencimiento que corresponde hoy (RF-010): 7 si faltan 7 días o menos, 30 si faltan 30 o menos, nada si falta
     * más o ya venció. Se evalúa por tramo y no por día exacto: si el proceso no corrió un día, la alerta no se pierde.
     */
    public static OptionalInt alertStage(LocalDate expiresOn, LocalDate today) {
        if (expiresOn == null) {
            return OptionalInt.empty();
        }
        long days = ChronoUnit.DAYS.between(today, expiresOn);
        if (days < 0 || days > FIRST_ALERT_DAYS) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(days <= SECOND_ALERT_DAYS ? SECOND_ALERT_DAYS : FIRST_ALERT_DAYS);
    }

    /**
     * ¿Puede el acudiente cargar una versión ahora? (RN-05, RF-006): la primera versión siempre; una nueva solo con ventana
     * abierta o ante vencimiento (documento vencido o a 30 días o menos de vencer).
     */
    public static boolean guardianMayUpload(boolean hasCurrent, LocalDate currentExpiresOn, boolean windowOpen, LocalDate today) {
        if (!hasCurrent || windowOpen) {
            return true;
        }
        return currentExpiresOn != null && ChronoUnit.DAYS.between(today, currentExpiresOn) <= FIRST_ALERT_DAYS;
    }
}
