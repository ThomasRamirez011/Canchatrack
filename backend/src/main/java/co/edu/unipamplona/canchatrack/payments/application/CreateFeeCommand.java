package co.edu.unipamplona.canchatrack.payments.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateFeeCommand(UUID seasonId, String concept, String name, BigDecimal amount, LocalDate validFrom,
                               LocalDate validTo) {
}
