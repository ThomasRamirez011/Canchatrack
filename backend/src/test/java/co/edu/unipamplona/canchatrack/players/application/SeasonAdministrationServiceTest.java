package co.edu.unipamplona.canchatrack.players.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.players.application.CallAdminFakes.InMemorySeasons;
import co.edu.unipamplona.canchatrack.players.application.Fakes.RecordingAudit;
import co.edu.unipamplona.canchatrack.players.domain.SeasonStatus;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

class SeasonAdministrationServiceTest {

    private static final Instant NOW = Fakes.NOW;

    private final InMemorySeasons seasons = new InMemorySeasons();
    private final RecordingAudit audit = new RecordingAudit();
    private final AdminActor actor = new AdminActor(UUID.randomUUID(), null, "JUnit");
    private final SeasonAdministrationService service = new SeasonAdministrationService(seasons, audit);

    private static DomainException thrown(Runnable action) {
        try {
            action.run();
        } catch (DomainException e) {
            return e;
        }
        throw new AssertionError("se esperaba una DomainException");
    }

    @SuppressWarnings("unchecked")
    private static List<String> errorFields(DomainException e) {
        return ((List<Map<String, String>>) e.getExtensions().get("errors")).stream().map(m -> m.get("field")).toList();
    }

    private SeasonInfo create(String name) {
        return service.create(actor, new CreateSeasonCommand(name, NOW, NOW.plusSeconds(86_400L * 300)));
    }

    @Test
    void createsAPlannedSeasonAndAuditsIt() {
        SeasonInfo season = create("  Temporada 2027  ");

        assertThat(season.name()).isEqualTo("Temporada 2027");
        assertThat(season.status()).isEqualTo(SeasonStatus.PLANNED);
        assertThat(audit.inTransaction).hasSize(1);
        assertThat(audit.inTransaction.get(0).action()).isEqualTo("SEASON_CREATED");
        assertThat(audit.inTransaction.get(0).actorRole()).isEqualTo("COORDINATOR");
    }

    @Test
    void aDuplicateNameIsAFieldError() {
        create("Temporada 2027");

        DomainException e = thrown(() -> create("Temporada 2027"));

        assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(errorFields(e)).contains("name");
        assertThat(audit.inTransaction).hasSize(1);
    }

    @Test
    void anInvalidRangeAndNameAreReportedTogether() {
        DomainException e = thrown(() -> service.create(actor, new CreateSeasonCommand(" ", NOW, NOW)));

        assertThat(errorFields(e)).contains("name", "endAt");
        assertThat(seasons.rows.size()).isEqualTo(0);
    }

    @Test
    void activatingMovesPlannedToActiveOnce() {
        SeasonInfo season = create("Temporada 2027");

        SeasonInfo active = service.activate(actor, season.id());

        assertThat(active.status()).isEqualTo(SeasonStatus.ACTIVE);
        assertThat(audit.inTransaction.get(audit.inTransaction.size() - 1).action()).isEqualTo("SEASON_ACTIVATED");
        assertThat(thrown(() -> service.activate(actor, season.id())).getCode()).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);
    }

    @Test
    void aClosedSeasonCannotBeActivated() {
        SeasonInfo closed = seasons.add(SeasonStatus.CLOSED);

        assertThat(thrown(() -> service.activate(actor, closed.id())).getCode()).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);
    }

    @Test
    void anUnknownSeasonIsNotFound() {
        assertThat(thrown(() -> service.activate(actor, UUID.randomUUID())).getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void listsNewestFirst() {
        service.create(actor, new CreateSeasonCommand("Vieja", NOW.minusSeconds(100), NOW.plusSeconds(100)));
        service.create(actor, new CreateSeasonCommand("Nueva", NOW, NOW.plusSeconds(100)));

        assertThat(service.list().get(0).name()).isEqualTo("Nueva");
    }
}
