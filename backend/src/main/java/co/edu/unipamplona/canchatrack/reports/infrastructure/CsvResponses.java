package co.edu.unipamplona.canchatrack.reports.infrastructure;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import co.edu.unipamplona.canchatrack.reports.application.ReportsActor;
import co.edu.unipamplona.canchatrack.shared.security.AuthenticatedUser;
import co.edu.unipamplona.canchatrack.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;

/** Piezas comunes de las exportaciones CSV: quién descarga (para la auditoría) y cómo se entrega el archivo. */
final class CsvResponses {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");
    /** Marca de orden de bytes: sin ella Excel abre el CSV UTF-8 con las tildes rotas. */
    private static final String BOM = "\uFEFF";

    private CsvResponses() {
    }

    /** Actor autenticado con su rol real (coordinación si lo tiene; si no, comité). Debe llamarse con sesión válida. */
    static ReportsActor actor(HttpServletRequest http) {
        AuthenticatedUser user = CurrentUser.require();
        String role = user.roles().contains("COORDINATOR") ? "COORDINATOR" : "COMMITTEE";
        return new ReportsActor(user.id(), role, remoteAddress(http), http.getHeader("User-Agent"));
    }

    static ResponseEntity<byte[]> csv(String fileName, String content) {
        return ResponseEntity.ok()
                .contentType(CSV)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .body((BOM + content).getBytes(StandardCharsets.UTF_8));
    }

    private static InetAddress remoteAddress(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) {
            return null;
        }
        try {
            return InetAddress.getByName(remote);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
