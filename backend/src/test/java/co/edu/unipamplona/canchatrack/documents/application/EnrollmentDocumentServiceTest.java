package co.edu.unipamplona.canchatrack.documents.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.documents.api.EnrollmentDocuments.Attached;
import co.edu.unipamplona.canchatrack.documents.api.EnrollmentDocuments.Attachment;
import co.edu.unipamplona.canchatrack.documents.api.EnrollmentDocuments.Problem;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Documentos iniciales de la radicación con dobles en memoria: archivo propio y sin uso previo, tipo activo y sin repetir,
 * tamaño, formato y vigencia, y la regla de ventana cuando el expediente ya tenía una versión (RN-05). La base real la
 * cubre {@code EnrollmentDocumentsIT}.
 */
class EnrollmentDocumentServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T17:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    private final UUID guardianUser = UUID.randomUUID();
    private final UUID otherUser = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();

    private final FakeStore store = new FakeStore();
    private final FakeFiles files = new FakeFiles();
    private final Recorder audit = new Recorder();
    private final EnrollmentDocumentService service;

    private final DocumentTypeInfo medical;
    private final DocumentTypeInfo photo;
    private final DocumentTypeInfo registry;

    EnrollmentDocumentServiceTest() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new EnrollmentDocumentService(store, files, audit,
                new DocumentSettings(ZoneId.of("America/Bogota"), 10L * 1024 * 1024), clock);
        store.players.add(player);
        medical = store.addType("MEDICAL_CERTIFICATE", "Certificado médico", true, true, null, true, null, List.of());
        photo = store.addType("ID_PHOTO", "Foto del jugador", false, false, null, false, 1_000L, List.of("image/png"));
        registry = store.addType("CIVIL_REGISTRY", "Registro civil", true, false, null, false, null, List.of());
    }

    private Attachment attachment(DocumentTypeInfo type, StoredFile file, LocalDate expiresOn) {
        return new Attachment(type.id(), file == null ? null : file.id(), TODAY.minusDays(2), expiresOn, null);
    }

    private static List<String> fields(List<Problem> problems) {
        return problems.stream().map(Problem::field).toList();
    }

    private static DomainException thrown(Runnable action) {
        try {
            action.run();
        } catch (DomainException e) {
            return e;
        }
        throw new AssertionError("se esperaba DomainException");
    }

    @SuppressWarnings("unchecked")
    private static List<String> errorFields(DomainException e) {
        return ((List<Map<String, String>>) e.getExtensions().get("errors")).stream().map(m -> m.get("field")).toList();
    }

    // ---------- validación ----------

    @Test
    void withoutAttachmentsThereIsNothingToCheckAndNothingIsCreated() {
        assertThat(service.check(guardianUser, null)).isEmpty();
        assertThat(service.check(guardianUser, List.of())).isEmpty();

        Attached attached = service.attach(guardianUser, player, List.of(), null, "test");

        assertThat(attached.count()).isZero();
        assertThat(store.versions).isEmpty();
        assertThat(audit.records).isEmpty();
        // Sin adjuntos, igual informa los obligatorios pendientes: el front sabe qué falta.
        assertThat(attached.missingMandatory()).containsExactly("Certificado médico", "Registro civil");
    }

    @Test
    void aValidAttachmentIsAcceptedWhenTheFileIsTheGuardiansAndUnused() {
        StoredFile file = files.add(guardianUser, "application/pdf", 500);

        assertThat(service.check(guardianUser, List.of(attachment(medical, file, TODAY.plusDays(200))))).isEmpty();
    }

    @Test
    void aFileFromAnotherUserAndAMissingFileAnswerTheSameWay() {
        StoredFile foreign = files.add(otherUser, "application/pdf", 500);
        Attachment ghost = new Attachment(medical.id(), UUID.randomUUID(), TODAY, TODAY.plusDays(100), null);

        List<Problem> problems = service.check(guardianUser,
                List.of(attachment(registry, foreign, null), ghost));

        assertThat(problems).extracting(Problem::field).containsExactly("documents[0].fileId", "documents[1].fileId");
        assertThat(problems).extracting(Problem::message).containsOnly("El archivo no existe.");
    }

    @Test
    void aFileAlreadyBackingADocumentCannotBeReused() {
        StoredFile file = files.add(guardianUser, "application/pdf", 500);
        store.insertVersion(UUID.randomUUID(), registry.id(), file.id(), null, null, null, guardianUser);

        List<Problem> problems = service.check(guardianUser, List.of(attachment(medical, file, TODAY.plusDays(100))));

        assertThat(problems).singleElement().satisfies(p -> {
            assertThat(p.field()).isEqualTo("documents[0].fileId");
            assertThat(p.message()).contains("ya está asociado");
        });
    }

    @Test
    void theSameTypeOrTheSameFileTwiceInOneFilingIsRejected() {
        StoredFile a = files.add(guardianUser, "application/pdf", 500);
        StoredFile b = files.add(guardianUser, "application/pdf", 500);

        List<Problem> sameType = service.check(guardianUser,
                List.of(attachment(registry, a, null), attachment(registry, b, null)));
        assertThat(fields(sameType)).containsExactly("documents[1].documentTypeId");

        List<Problem> sameFile = service.check(guardianUser,
                List.of(attachment(registry, a, null), attachment(medical, a, TODAY.plusDays(100))));
        assertThat(fields(sameFile)).containsExactly("documents[1].fileId");
    }

    @Test
    void anUnknownInactiveOrMissingTypeAndAMissingFileAreAllReportedTogether() {
        DocumentTypeInfo retired = store.addType("OLD", "Antiguo", false, false, null, false, null, List.of());
        store.types.put(retired.id(), new DocumentTypeInfo(retired.id(), "OLD", "Antiguo", false, false, null, false, null,
                List.of(), false));
        StoredFile file = files.add(guardianUser, "application/pdf", 500);

        List<Problem> problems = service.check(guardianUser, List.of(
                new Attachment(UUID.randomUUID(), file.id(), null, null, null),
                new Attachment(retired.id(), null, null, null, null),
                new Attachment(null, null, null, null, null)));

        assertThat(fields(problems)).containsExactly("documents[0].documentTypeId", "documents[1].documentTypeId",
                "documents[1].fileId", "documents[2].documentTypeId", "documents[2].fileId");
    }

    @Test
    void sizeFormatAndExpiryRulesOfTheTypeApplyToEachAttachment() {
        StoredFile bigPdf = files.add(guardianUser, "application/pdf", 5_000);
        StoredFile jpeg = files.add(guardianUser, "image/jpeg", 100);
        StoredFile pdf = files.add(guardianUser, "application/pdf", 100);

        List<Problem> problems = service.check(guardianUser, List.of(
                attachment(photo, bigPdf, null),            // pdf y mayor que el límite de 1000 bytes del tipo
                attachment(photo, jpeg, null),              // repite el tipo: solo se informa el tipo
                new Attachment(medical.id(), pdf.id(), TODAY, null, null)));   // vence y no trae vencimiento

        assertThat(fields(problems)).containsExactly("documents[0].fileId", "documents[0].fileId",
                "documents[1].documentTypeId", "documents[2].expiresOn");
    }

    @Test
    void anExpiredCertificateAndAFutureIssueDateAreRejected() {
        StoredFile file = files.add(guardianUser, "application/pdf", 100);

        List<Problem> problems = service.check(guardianUser, List.of(
                new Attachment(medical.id(), file.id(), TODAY.plusDays(1), TODAY.minusDays(1), null)));

        assertThat(fields(problems)).contains("documents[0].issuedOn", "documents[0].expiresOn");
    }

    @Test
    void moreThanTwentyAttachmentsAreRejectedWithASingleError() {
        List<Attachment> many = new ArrayList<>();
        for (int i = 0; i < EnrollmentDocumentService.MAX_ATTACHMENTS + 1; i++) {
            many.add(new Attachment(UUID.randomUUID(), UUID.randomUUID(), null, null, null));
        }

        assertThat(fields(service.check(guardianUser, many))).containsExactly("documents");
    }

    @Test
    void aNullElementIsReportedAtItsIndex() {
        List<Attachment> list = new ArrayList<>();
        list.add(null);

        assertThat(fields(service.check(guardianUser, list))).containsExactly("documents[0]");
    }

    // ---------- creación ----------

    @Test
    void attachCreatesTheFirstVersionOfEachTypeAuditsAndReportsWhatIsStillMissing() {
        StoredFile certificate = files.add(guardianUser, "application/pdf", 400);
        StoredFile picture = files.add(guardianUser, "image/png", 300);

        Attached attached = service.attach(guardianUser, player, List.of(
                new Attachment(medical.id(), certificate.id(), TODAY.minusDays(3), TODAY.plusDays(300), "  vigente  "),
                attachment(photo, picture, null)), InetAddress.getLoopbackAddress(), "JUnit");

        assertThat(attached.count()).isEqualTo(2);
        // El registro civil es obligatorio y no se adjuntó: la radicación se acepta y lo informa.
        assertThat(attached.missingMandatory()).containsExactly("Registro civil");
        assertThat(store.versions).hasSize(2);
        assertThat(store.versions).allSatisfy(v -> {
            assertThat(v.versionNumber()).isEqualTo(1);
            assertThat(v.uploadedBy()).isEqualTo(guardianUser);
            assertThat(v.playerId()).isEqualTo(player);
        });
        assertThat(store.versions.get(0).notes()).isEqualTo("vigente");
        assertThat(audit.records).extracting(AuditRecord::action)
                .containsExactly("DOCUMENT_VERSION_UPLOADED", "DOCUMENT_VERSION_UPLOADED");
        assertThat(audit.records.get(0).afterJson()).contains("\"source\":\"enrollment\"").contains("MEDICAL_CERTIFICATE");
    }

    @Test
    void attachingEverythingMandatoryLeavesNothingMissing() {
        StoredFile certificate = files.add(guardianUser, "application/pdf", 400);
        StoredFile civil = files.add(guardianUser, "application/pdf", 400);

        Attached attached = service.attach(guardianUser, player, List.of(
                attachment(medical, certificate, TODAY.plusDays(300)), attachment(registry, civil, null)), null, "test");

        assertThat(attached.missingMandatory()).isEmpty();
    }

    @Test
    void theExpiryIsDerivedFromTheTypeValidityWhenNotGiven() {
        DocumentTypeInfo periodic = store.addType("CONSENT_SCAN", "Consentimiento firmado", false, true, 365, false, null, List.of());
        StoredFile file = files.add(guardianUser, "application/pdf", 100);

        service.attach(guardianUser, player, List.of(
                new Attachment(periodic.id(), file.id(), TODAY.minusDays(10), null, null)), null, "test");

        assertThat(store.versions.get(0).expiresOn()).isEqualTo(TODAY.minusDays(10).plusDays(365));
    }

    @Test
    void attachFailsWholeWhenOneAttachmentIsInvalidAndCreatesNothing() {
        StoredFile good = files.add(guardianUser, "application/pdf", 100);

        DomainException e = thrown(() -> service.attach(guardianUser, player, List.of(
                attachment(registry, good, null),
                new Attachment(UUID.randomUUID(), UUID.randomUUID(), null, null, null)), null, "test"));

        assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(errorFields(e)).containsExactly("documents[1].documentTypeId", "documents[1].fileId");
        assertThat(store.versions).isEmpty();
        assertThat(audit.records).isEmpty();
    }

    @Test
    void aFileClaimedByAnotherFilingAfterTheCheckIsCaughtUnderTheLock() {
        StoredFile file = files.add(guardianUser, "application/pdf", 100);
        store.onLock = () -> store.insertVersion(UUID.randomUUID(), registry.id(), file.id(), null, null, null, guardianUser);

        DomainException e = thrown(() -> service.attach(guardianUser, player,
                List.of(attachment(medical, file, TODAY.plusDays(100))), null, "test"));

        assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(errorFields(e)).containsExactly("documents[0].fileId");
        assertThat(store.versions).hasSize(1); // solo la versión ajena
    }

    // ---------- expediente reutilizado (RN-05) ----------

    @Test
    void aReusedRecordWithACurrentVersionNeedsAWindowOrAnExpiryNearby() {
        StoredFile previous = files.add(guardianUser, "application/pdf", 100);
        store.insertVersion(player, medical.id(), previous.id(), TODAY.minusDays(100), TODAY.plusDays(300), null, guardianUser);
        StoredFile renewal = files.add(guardianUser, "application/pdf", 100);

        DomainException e = thrown(() -> service.attach(guardianUser, player,
                List.of(attachment(medical, renewal, TODAY.plusDays(600))), null, "test"));

        assertThat(e.getCode()).isEqualTo(ErrorCode.DOCUMENT_UPLOAD_NOT_ALLOWED);
        assertThat(store.versions).hasSize(1);
    }

    @Test
    void aReusedRecordAcceptsTheNextVersionWithAnOpenWindowOrAnExpiringDocument() {
        StoredFile previous = files.add(guardianUser, "application/pdf", 100);
        store.insertVersion(player, medical.id(), previous.id(), TODAY.minusDays(100), TODAY.plusDays(300), null, guardianUser);
        store.windows.add(new Object[]{medical.id(), player, NOW.minusSeconds(60), NOW.plusSeconds(3_600)});
        StoredFile renewal = files.add(guardianUser, "application/pdf", 100);

        service.attach(guardianUser, player, List.of(attachment(medical, renewal, TODAY.plusDays(600))), null, "test");
        assertThat(store.findVersions(player, medical.id())).hasSize(2);

        // Ahora sin ventana, pero la versión vigente vence en 10 días.
        store.windows.clear();
        StoredFile expiring = files.add(guardianUser, "application/pdf", 100);
        store.insertVersion(player, registry.id(), expiring.id(), null, TODAY.plusDays(10), null, guardianUser);
        StoredFile next = files.add(guardianUser, "application/pdf", 100);
        service.attach(guardianUser, player, List.of(attachment(registry, next, TODAY.plusDays(400))), null, "test");
        assertThat(store.findVersions(player, registry.id())).hasSize(2);
    }

    // ---------- dobles ----------

    private static final class Recorder implements AuditWriter {
        final List<AuditRecord> records = new ArrayList<>();

        @Override
        public void write(AuditRecord record) {
            records.add(record);
        }

        @Override
        public void writeIndependent(AuditRecord record) {
            records.add(record);
        }
    }

    private static final class FakeFiles implements StoredFileStore {
        final Map<UUID, StoredFile> files = new HashMap<>();

        StoredFile add(UUID owner, String contentType, long size) {
            return insert("archivo", contentType, size, "k/" + UUID.randomUUID(), "00", owner);
        }

        @Override
        public StoredFile insert(String originalName, String contentType, long sizeBytes, String storageKey, String sha256,
                                 UUID uploadedBy) {
            StoredFile file = new StoredFile(UUID.randomUUID(), originalName, contentType, sizeBytes, storageKey, sha256, uploadedBy, NOW);
            files.put(file.id(), file);
            return file;
        }

        @Override
        public Optional<StoredFile> find(UUID fileId) {
            return Optional.ofNullable(files.get(fileId));
        }

        @Override
        public boolean isHealthDocument(UUID fileId) {
            return false;
        }
    }

    private static final class FakeStore implements DocumentStore {
        final Map<UUID, DocumentTypeInfo> types = new LinkedHashMap<>();
        final Set<UUID> players = new HashSet<>();
        final List<Object[]> windows = new ArrayList<>();
        final List<DocumentVersionInfo> versions = new ArrayList<>();
        Runnable onLock = () -> { };

        DocumentTypeInfo addType(String code, String name, boolean mandatory, boolean requiresExpiry, Integer validityDays,
                                 boolean health, Long maxSize, List<String> formats) {
            DocumentTypeInfo type = new DocumentTypeInfo(UUID.randomUUID(), code, name, mandatory, requiresExpiry, validityDays, health,
                    maxSize, formats, true);
            types.put(type.id(), type);
            return type;
        }

        @Override
        public List<DocumentTypeInfo> findTypes(boolean activeOnly) {
            return types.values().stream().filter(t -> !activeOnly || t.active()).toList();
        }

        @Override
        public Optional<DocumentTypeInfo> findType(UUID id) {
            return Optional.ofNullable(types.get(id));
        }

        @Override
        public DocumentTypeInfo insertType(String code, String name, boolean mandatory, boolean requiresExpiry, Integer validityDays,
                                           boolean containsHealthData, Long maxSizeBytes, List<String> allowedContentTypes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateType(UUID id, String name, boolean mandatory, boolean requiresExpiry, Integer validityDays, Long maxSizeBytes,
                               List<String> allowedContentTypes, boolean active) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean playerExists(UUID playerId) {
            return players.contains(playerId);
        }

        @Override
        public boolean lockPlayer(UUID playerId) {
            return players.contains(playerId);
        }

        @Override
        public DocumentWindowInfo insertWindow(UUID documentTypeId, UUID playerId, Instant opensAt, Instant closesAt, String reason,
                                               UUID openedBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DocumentWindowInfo> findWindows(UUID documentTypeId, UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasOpenWindow(UUID documentTypeId, UUID playerId, Instant at) {
            return windows.stream().anyMatch(w -> w[0].equals(documentTypeId) && (w[1] == null || w[1].equals(playerId))
                    && !((Instant) w[2]).isAfter(at) && ((Instant) w[3]).isAfter(at));
        }

        @Override
        public Optional<DocumentVersionInfo> findCurrent(UUID playerId, UUID documentTypeId) {
            return findVersions(playerId, documentTypeId).stream().findFirst();
        }

        @Override
        public List<DocumentVersionInfo> findCurrentOfPlayer(UUID playerId) {
            return types.keySet().stream().map(t -> findCurrent(playerId, t)).flatMap(Optional::stream).toList();
        }

        @Override
        public List<DocumentVersionInfo> findVersions(UUID playerId, UUID documentTypeId) {
            return versions.stream().filter(v -> v.playerId().equals(playerId) && v.documentTypeId().equals(documentTypeId))
                    .sorted((a, b) -> Integer.compare(b.versionNumber(), a.versionNumber())).toList();
        }

        @Override
        public Optional<DocumentVersionInfo> findVersion(UUID versionId) {
            return versions.stream().filter(v -> v.id().equals(versionId)).findFirst();
        }

        @Override
        public DocumentVersionInfo insertVersion(UUID playerId, UUID documentTypeId, UUID fileId, LocalDate issuedOn,
                                                 LocalDate expiresOn, String notes, UUID uploadedBy) {
            int number = findVersions(playerId, documentTypeId).size() + 1;
            DocumentVersionInfo version = new DocumentVersionInfo(UUID.randomUUID(), playerId, documentTypeId, number, fileId,
                    "archivo", "application/pdf", 100, issuedOn, expiresOn, notes, uploadedBy, NOW);
            versions.add(version);
            return version;
        }

        @Override
        public boolean isFileAttached(UUID fileId) {
            return versions.stream().anyMatch(v -> v.fileId().equals(fileId));
        }

        @Override
        public void lockFile(UUID fileId) {
            onLock.run();
            onLock = () -> { };
        }

        @Override
        public List<String> findMissingMandatory(UUID playerId, LocalDate onDate) {
            return types.values().stream().filter(t -> t.active() && t.mandatory())
                    .filter(t -> findCurrent(playerId, t.id())
                            .filter(v -> !t.requiresExpiry() || (v.expiresOn() != null && !v.expiresOn().isBefore(onDate)))
                            .isEmpty())
                    .map(DocumentTypeInfo::name).sorted().toList();
        }

        @Override
        public List<ExpiringDocument> findExpiring(LocalDate from, int withinDays) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isHeadCoachOf(UUID userId, UUID playerId) {
            return false;
        }
    }
}
