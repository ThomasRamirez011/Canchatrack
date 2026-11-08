package co.edu.unipamplona.canchatrack.players.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.players.application.CallAdminFakes.InMemoryCategories;
import co.edu.unipamplona.canchatrack.players.application.Fakes.InMemoryPlayers;
import co.edu.unipamplona.canchatrack.players.application.Fakes.RecordingAudit;
import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

class CategoryAdministrationServiceTest {

    private final InMemoryCategories categories = new InMemoryCategories();
    private final RecordingAudit audit = new RecordingAudit();
    private final AdminActor actor = new AdminActor(UUID.randomUUID(), null, "JUnit");
    private CategoryAdministrationService service;

    @BeforeEach
    void setUp() {
        service = new CategoryAdministrationService(categories, audit);
    }

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

    private CategoryInfo create(String name, int min, int max, int capacity) {
        return service.create(actor, new CreateCategoryCommand(name, min, max, capacity, null));
    }

    private static UpdateCategoryCommand patch(String name, Integer min, Integer max, Integer capacity, Boolean active) {
        return new UpdateCategoryCommand(name, min, max, capacity, null, false, active);
    }

    private AuditRecord lastAudit() {
        return audit.inTransaction.get(audit.inTransaction.size() - 1);
    }

    // --- crear ---

    @Test
    void createsACategoryAndAuditsIt() {
        CategoryInfo created = create("  Sub-12  ", 2014, 2015, 20);

        assertThat(created.name()).isEqualTo("Sub-12");
        assertThat(created.capacity()).isEqualTo(20);
        assertThat(created.available()).isEqualTo(20);
        assertThat(created.active()).isTrue();
        assertThat(lastAudit().action()).isEqualTo("CATEGORY_CREATED");
        assertThat(lastAudit().actorRole()).isEqualTo("COORDINATOR");
    }

    @Test
    void invalidDataIsReportedFieldByField() {
        DomainException e = thrown(() -> service.create(actor, new CreateCategoryCommand(" ", 2016, 2015, 0, null)));

        assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(errorFields(e)).contains("name", "birthYearMax", "capacity");
        assertThat(categories.rows.size()).isEqualTo(0);
    }

    @Test
    void missingValuesAreFieldErrorsToo() {
        DomainException e = thrown(() -> service.create(actor, new CreateCategoryCommand("X", null, null, null, null)));

        assertThat(errorFields(e)).contains("birthYearMin", "birthYearMax", "capacity");
    }

    @Test
    void anOverlappingActiveRangeIsRejectedWithTheConflictingCategoryNamed() {
        create("Sub-12", 2014, 2015, 20);

        DomainException e = thrown(() -> create("Otra", 2015, 2016, 20));

        assertThat(errorFields(e)).contains("birthYearMin");
        assertThat(e.getExtensions().toString().contains("Sub-12")).isTrue();
        assertThat(categories.rows.size()).isEqualTo(1);
    }

    @Test
    void adjacentRangesAreFine() {
        create("Sub-12", 2014, 2015, 20);

        assertThat(create("Sub-14", 2012, 2013, 20).name()).isEqualTo("Sub-14");
    }

    @Test
    void aDuplicateNameIsAFieldError() {
        create("Sub-12", 2014, 2015, 20);

        assertThat(errorFields(thrown(() -> create("Sub-12", 2012, 2013, 20)))).contains("name");
    }

    @Test
    void theBackstopForARaceIsAlsoAFieldError() {
        // Otra transacción inserta el solape entre la verificación y el INSERT: la base responde 23P01.
        InMemoryCategories racing = new InMemoryCategories() {
            @Override
            public Optional<CategoryInfo> findActiveOverlapping(int min, int max, UUID exclude) {
                return Optional.empty();
            }
        };
        racing.add("Sub-12", 2014, 2015, 20, 0, true);
        CategoryAdministrationService racingService = new CategoryAdministrationService(racing, audit);

        DomainException e = thrown(() -> racingService.create(actor, new CreateCategoryCommand("Otra", 2015, 2016, 20, null)));

        assertThat(errorFields(e)).contains("birthYearMin");
    }

    @Test
    void theNextCategoryMustExist() {
        DomainException e = thrown(() -> service.create(actor,
                new CreateCategoryCommand("Sub-12", 2014, 2015, 20, UUID.randomUUID())));

        assertThat(errorFields(e)).contains("nextCategoryId");
    }

    // --- modificar ---

    @Test
    void updatesCapacityAndNameKeepingAnAuditTrailWithBeforeAndAfter() {
        CategoryInfo c = create("Sub-12", 2014, 2015, 20);

        CategoryInfo updated = service.update(actor, c.id(), patch("Sub-12 A", null, null, 25, null));

        assertThat(updated.capacity()).isEqualTo(25);
        assertThat(updated.name()).isEqualTo("Sub-12 A");
        assertThat(lastAudit().action()).isEqualTo("CATEGORY_UPDATED");
        assertThat(lastAudit().beforeJson().contains("\"capacity\":20")).isTrue();
        assertThat(lastAudit().afterJson().contains("\"capacity\":25")).isTrue();
    }

    @Test
    void anUpdateWithNothingToChangeIsRejectedAndAnIdenticalOneLeavesNoTrace() {
        CategoryInfo c = create("Sub-12", 2014, 2015, 20);
        int before = audit.inTransaction.size();

        assertThat(errorFields(thrown(() -> service.update(actor, c.id(), patch(null, null, null, null, null))))).contains("body");
        service.update(actor, c.id(), patch("Sub-12", 2014, 2015, 20, true));

        assertThat(audit.inTransaction).hasSize(before);
    }

    @Test
    void theCapacityCannotDropBelowThePlayersAlreadyAssigned() {
        CategoryInfo c = categories.add("Sub-12", 2014, 2015, 20, 15, true);

        DomainException e = thrown(() -> service.update(actor, c.id(), patch(null, null, null, 10, null)));
        assertThat(errorFields(e)).contains("capacity");

        assertThat(service.update(actor, c.id(), patch(null, null, null, 15, null)).capacity()).isEqualTo(15);
    }

    @Test
    void changingTheRangeIntoAnotherActiveCategoryIsRejected() {
        create("Sub-12", 2014, 2015, 20);
        CategoryInfo other = create("Sub-14", 2012, 2013, 20);

        DomainException e = thrown(() -> service.update(actor, other.id(), patch(null, 2013, 2014, null, null)));

        assertThat(errorFields(e)).contains("birthYearMin");
    }

    @Test
    void aCategoryCanBeDeactivatedAndItsRangeIsReleased() {
        CategoryInfo old = create("Sub-12", 2014, 2015, 20);
        service.update(actor, old.id(), patch(null, null, null, null, false));

        assertThat(create("Sub-12 nueva", 2014, 2015, 20).active()).isTrue();
        // reactivar la antigua ahora choca con la nueva
        assertThat(errorFields(thrown(() -> service.update(actor, old.id(), patch(null, null, null, null, true)))))
                .contains("birthYearMin");
    }

    @Test
    void aDeactivatedCategoryMayKeepAnOverlappingRangeWhileInactive() {
        create("Sub-12", 2014, 2015, 20);
        CategoryInfo old = categories.add("Histórica", 2014, 2015, 20, 0, false);

        CategoryInfo renamed = service.update(actor, old.id(), patch("Histórica 2025", null, null, null, null));

        assertThat(renamed.active()).isFalse();
    }

    @Test
    void promotionChainsMustNotLoop() {
        CategoryInfo a = create("Sub-12", 2014, 2015, 20);
        CategoryInfo b = create("Sub-14", 2012, 2013, 20);

        service.update(actor, a.id(), new UpdateCategoryCommand(null, null, null, null, b.id(), false, null));
        DomainException loop = thrown(() -> service.update(actor, b.id(),
                new UpdateCategoryCommand(null, null, null, null, a.id(), false, null)));
        DomainException self = thrown(() -> service.update(actor, a.id(),
                new UpdateCategoryCommand(null, null, null, null, a.id(), false, null)));

        assertThat(errorFields(loop)).contains("nextCategoryId");
        assertThat(errorFields(self)).contains("nextCategoryId");
    }

    @Test
    void theNextCategoryCanBeClearedButNotSetAndClearedAtOnce() {
        CategoryInfo a = create("Sub-12", 2014, 2015, 20);
        CategoryInfo b = create("Sub-14", 2012, 2013, 20);
        service.update(actor, a.id(), new UpdateCategoryCommand(null, null, null, null, b.id(), false, null));

        CategoryInfo cleared = service.update(actor, a.id(), new UpdateCategoryCommand(null, null, null, null, null, true, null));
        assertThat(cleared.nextCategoryId()).isNull();

        DomainException both = thrown(() -> service.update(actor, a.id(),
                new UpdateCategoryCommand(null, null, null, null, b.id(), true, null)));
        assertThat(errorFields(both)).contains("nextCategoryId");
    }

    @Test
    void unknownCategoriesAreNotFound() {
        UUID unknown = UUID.randomUUID();

        assertThat(thrown(() -> service.get(unknown)).getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(thrown(() -> service.update(actor, unknown, patch("X", null, null, null, null))).getCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void listingHidesInactiveCategoriesUnlessAsked() {
        CategoryInfo old = create("Sub-12", 2014, 2015, 20);
        create("Sub-14", 2012, 2013, 20);
        service.update(actor, old.id(), patch(null, null, null, null, false));

        assertThat(service.list(false)).hasSize(1);
        assertThat(service.list(true)).hasSize(2);
    }

    // --- sugerencia (RF-046) ---

    @Test
    void suggestsTheActiveCategoryThatCoversTheBirthYearWithItsAvailability() {
        InMemoryPlayers players = new InMemoryPlayers();
        CategoryQueryService queries = new CategoryQueryService(categories, players);
        categories.add("Sub-12", 2014, 2015, 20, 18, true);
        InMemoryPlayers.Row player = players.seed("TI-1", PlayerStatus.SUBMITTED, null); // nace en 2014

        CategorySuggestion suggestion = queries.suggestFor(player.id());

        assertThat(suggestion.birthYear()).isEqualTo(2014);
        assertThat(suggestion.suggested().name()).isEqualTo("Sub-12");
        assertThat(suggestion.suggested().available()).isEqualTo(2);
    }

    @Test
    void noSuggestionWhenNoActiveCategoryCoversTheYearAndUnknownPlayersAreNotFound() {
        InMemoryPlayers players = new InMemoryPlayers();
        CategoryQueryService queries = new CategoryQueryService(categories, players);
        categories.add("Sub-12", 2014, 2015, 20, 0, false); // inactiva
        InMemoryPlayers.Row player = players.seed("TI-1", PlayerStatus.SUBMITTED, null);

        assertThat(queries.suggestFor(player.id()).suggested()).isNull();
        assertThat(thrown(() -> queries.suggestFor(UUID.randomUUID())).getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
