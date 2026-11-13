package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.documents.api.EnrollmentDocuments.Attachment;
import co.edu.unipamplona.canchatrack.players.application.EnrollmentService;
import co.edu.unipamplona.canchatrack.players.application.SubmitEnrollmentCommand;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.EnrollmentResponse;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.PlayerData;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.SubmitEnrollmentRequest;
import co.edu.unipamplona.canchatrack.shared.security.CurrentUser;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping(ApiPaths.V1 + "/enrollments")
public class EnrollmentController {

    private final EnrollmentService enrollment;

    public EnrollmentController(EnrollmentService enrollment) {
        this.enrollment = enrollment;
    }

    /** Solo un acudiente radica inscripciones (DAD CU-02). */
    @PostMapping
    @PreAuthorize("hasRole('GUARDIAN')")
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollmentResponse submit(@Valid @RequestBody SubmitEnrollmentRequest request, HttpServletRequest http) {
        PlayerData player = request.player();
        SubmitEnrollmentCommand command = new SubmitEnrollmentCommand(request.callId(), player.firstName(),
                player.lastName(), player.documentType(), player.documentNumber(), player.birthDate(),
                request.relationship(), Boolean.TRUE.equals(request.acceptDataProcessing()),
                Boolean.TRUE.equals(request.acceptParticipation()), attachments(request));
        return EnrollmentResponse.from(enrollment.submit(
                CurrentUser.require().id(), command, RequestOrigins.remoteAddress(http), http.getHeader("User-Agent")));
    }

    private static List<Attachment> attachments(SubmitEnrollmentRequest request) {
        if (request.documents() == null) {
            return List.of();
        }
        // Un elemento nulo del arreglo se conserva: el servicio lo informa como error de ese índice.
        return request.documents().stream()
                .map(d -> d == null ? null : new Attachment(d.documentTypeId(), d.fileId(), d.issuedOn(), d.expiresOn(), d.notes()))
                .toList();
    }
}
