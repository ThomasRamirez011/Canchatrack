package co.edu.unipamplona.canchatrack.payments.infrastructure;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.payments.application.CreateFeeCommand;
import co.edu.unipamplona.canchatrack.payments.application.FeeService;
import co.edu.unipamplona.canchatrack.payments.application.PaymentService;
import co.edu.unipamplona.canchatrack.payments.application.PaymentsActor;
import co.edu.unipamplona.canchatrack.payments.application.RecordPaymentCommand;
import co.edu.unipamplona.canchatrack.payments.infrastructure.PaymentsDtos.AccountResponse;
import co.edu.unipamplona.canchatrack.payments.infrastructure.PaymentsDtos.CreateFeeRequest;
import co.edu.unipamplona.canchatrack.payments.infrastructure.PaymentsDtos.FeeResponse;
import co.edu.unipamplona.canchatrack.payments.infrastructure.PaymentsDtos.PaymentResponse;
import co.edu.unipamplona.canchatrack.payments.infrastructure.PaymentsDtos.RecordPaymentRequest;
import co.edu.unipamplona.canchatrack.payments.infrastructure.PaymentsDtos.VoidPaymentRequest;
import co.edu.unipamplona.canchatrack.shared.security.AuthenticatedUser;
import co.edu.unipamplona.canchatrack.shared.security.CurrentUser;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;

/** Tarifas, pagos inmutables y estado de cuenta (DDS UC-04; RN-16). */
@RestController
@RequestMapping(ApiPaths.V1)
public class PaymentController {

    private final FeeService fees;
    private final PaymentService payments;

    public PaymentController(FeeService fees, PaymentService payments) {
        this.fees = fees;
        this.payments = payments;
    }

    @PostMapping("/fees")
    @PreAuthorize("hasRole('COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public FeeResponse createFee(@RequestBody CreateFeeRequest request, HttpServletRequest http) {
        return FeeResponse.from(fees.create(actor(http), new CreateFeeCommand(request.seasonId(), request.concept(),
                request.name(), request.amount(), request.validFrom(), request.validTo())));
    }

    @GetMapping("/fees")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE')")
    public List<FeeResponse> listFees(@RequestParam(required = false) UUID seasonId) {
        return fees.list(seasonId).stream().map(FeeResponse::from).toList();
    }

    @PostMapping("/players/{playerId}/payments")
    @PreAuthorize("hasRole('COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse record(@PathVariable UUID playerId, @RequestBody RecordPaymentRequest request,
                                  HttpServletRequest http) {
        return PaymentResponse.from(payments.record(actor(http), playerId, new RecordPaymentCommand(request.chargeId(),
                request.amount(), request.method(), request.reference(), request.paidAt(), request.receiptFileId())));
    }

    @PostMapping("/payments/{paymentId}/void")
    @PreAuthorize("hasRole('COORDINATOR')")
    public PaymentResponse voidPayment(@PathVariable UUID paymentId, @RequestBody VoidPaymentRequest request,
                                       HttpServletRequest http) {
        return PaymentResponse.from(payments.voidPayment(actor(http), paymentId, request.reason()));
    }

    /** Coordinador y comité, cualquier jugador; el acudiente, solo los vinculados (PA-04). */
    @GetMapping("/players/{playerId}/account")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE', 'GUARDIAN')")
    public AccountResponse account(@PathVariable UUID playerId) {
        AuthenticatedUser user = CurrentUser.require();
        return AccountResponse.from(payments.account(user.id(), user.roles(), playerId));
    }

    private static PaymentsActor actor(HttpServletRequest request) {
        return new PaymentsActor(CurrentUser.require().id(), remoteAddress(request), request.getHeader("User-Agent"));
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
