package co.edu.unipamplona.canchatrack.payments.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record FeeInfo(UUID id, UUID seasonId, String concept, String name, BigDecimal amount, String currency,
                      LocalDate validFrom, LocalDate validTo, boolean active) {
}
