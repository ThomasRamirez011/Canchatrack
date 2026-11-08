package co.edu.unipamplona.canchatrack.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.documents.api.ConsentKind;
import co.edu.unipamplona.canchatrack.documents.application.ConsentService;
import co.edu.unipamplona.canchatrack.documents.application.ConsentStore;

class ConsentServiceTest {

    private final List<String> stored = new ArrayList<>();
    private final ConsentStore store = (guardianId, playerId, kind, versionText) -> stored.add(kind + ":" + versionText);
    private final ConsentService service = new ConsentService(store);

    @Test
    void recordsTheAcceptedTextVersion() {
        service.recordAuthenticatedAcceptance(UUID.randomUUID(), UUID.randomUUID(), ConsentKind.DATA_PROCESSING, "v1");

        assertThat(stored).containsExactly("DATA_PROCESSING:v1");
    }

    @Test
    void rejectsMissingOrOversizedVersionText() {
        UUID g = UUID.randomUUID();
        UUID p = UUID.randomUUID();
        assertThatThrownBy(() -> service.recordAuthenticatedAcceptance(g, p, ConsentKind.PARTICIPATION, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordAuthenticatedAcceptance(g, p, ConsentKind.PARTICIPATION, "x".repeat(51)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordAuthenticatedAcceptance(g, p, null, "v1"))
                .isInstanceOf(NullPointerException.class);
        assertThat(stored).isEmpty();
    }
}
