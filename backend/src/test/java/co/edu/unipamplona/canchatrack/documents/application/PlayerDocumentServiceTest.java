package co.edu.unipamplona.canchatrack.documents.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.documents.application.DocumentCommands.UploadVersion;
import co.edu.unipamplona.canchatrack.documents.domain.DocumentRules.Status;
import co.edu.unipamplona.canchatrack.identity.api.PlayerAccessPolicy;
import co.edu.unipamplona.canchatrack.identity.api.PlayerScope;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Reglas de carga y de acceso a documentos con dobles en memoria: ventana o vencimiento (RN-05), versiones que se acumulan
 * (RF-008) y quién ve los documentos de salud (RF-009, RN-17). La base real la cubre {@code DocumentsIT}.
 */
class PlayerDocumentServiceTest {

    private static final byte[] PDF = "%PDF-1.4 certificado".getBytes();
    private static final Instant NOW = Instant.parse("2026-11-12T17:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    private final UUID player = UUID.randomUUID();
    private final UUID guardianUser = UUID.randomUUID();
    private final UUID outsiderGuardian = UUID.randomUUID();
    private final UUID coordinatorUser = UUID.randomUUID();
    private final UUID committeeUser = UUID.randomUUID();
    private final UUID headCoach = UUID.randomUUID();
    private final UUID otherCoach = UUID.randomUUID();

    private final FakeStore store = new FakeStore();
    private final Recorder audit = new Recorder();
    private final Set<UUID> linkedGuardians = new HashSet<>(Set.of(guardianUser));
    private final PlayerDocumentService service;

    private final DocumentTypeInfo medical;
    private final DocumentTypeInfo photo;

    PlayerDocumentServiceTest() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        DocumentSettings settings = new DocumentSettings(ZoneId.of("America/Bogota"), 10L * 1024 * 1024);
        FileService files = new FileService(new FakeFiles(), new FakeContent(), new FileSettings(settings.maxFileSizeBytes()));
        PlayerAccessPolicy access = new PlayerAccessPolicy() {
            @Override
            public boolean canAccess(UUID userId, Set<String> roleCodes, UUID playerId) {
                if (roleCodes.contains("COORDINATOR") || roleCodes.contains("COMMITTEE")) {
                    return true;
                }
                return roleCodes.contains("GUARDIAN") && playerId.equals(player) && linkedGuardians.contains(userId);
            }

            @Override
            public PlayerScope scopeOf(Set<String> roleCodes) {
                return PlayerScope.NONE;
            }
        };
        service = new PlayerDocumentService(store, files, access, audit, settings, clock);
        store.players.add(player);
        store.headCoaches.put(player, headCoach);
        medical = store.addType("MEDICAL_CERTIFICATE", "Certificado médico", true, true, true, null);
        photo = store.addType("ID_PHOTO", "Foto del jugador", false, false, false, List.of("image/png"));
    }

    private DocumentActor actor(UUID id, String role) {
        return new DocumentActor(id, Set.of(role), null, "test");
    }

    private UploadVersion upload(DocumentTypeInfo type, LocalDate expiresOn) {
        return new UploadVersion(player, type.id(), "certificado.pdf", "application/pdf", PDF, TODAY.minusDays(1), expiresOn, null);
    }

    private static ErrorCode codeOf(Supplier<?> action) {
        try {
            action.get();
        } catch (DomainException e) {
            return e.getCode();
        }
        return null;
    }

    private static List<Object> auditedActions(Recorder audit) {
        return audit.records.stream().map(AuditRecord::action).collect(java.util.stream.Collectors.toList());
    }

    // ---------- carga ----------

    @Test
    void theGuardianMayUploadTheFirstVersionButNeedsAWindowOrAnExpiryForTheNext() {
        DocumentActor guardian = actor(guardianUser, "GUARDIAN");

        DocumentVersionInfo first = service.upload(guardian, upload(medical, TODAY.plusDays(300)));
        assertThat(first.versionNumber()).isEqualTo(1);

        // Vigente por 300 días y sin ventana: no puede reemplazarlo (RN-05).
        assertThat(codeOf(() -> service.upload(guardian, upload(medical, TODAY.plusDays(600)))))
                .isEqualTo(ErrorCode.DOCUMENT_UPLOAD_NOT_ALLOWED);

        // Con una ventana abierta sí; la versión anterior se conserva (RF-008).
        store.windows.add(new Object[]{medical.id(), player, NOW.minusSeconds(60), NOW.plusSeconds(3600)});
        DocumentVersionInfo second = service.upload(guardian, upload(medical, TODAY.plusDays(600)));
        assertThat(second.versionNumber()).isEqualTo(2);
        assertThat(store.findVersions(player, medical.id())).hasSize(2);
        assertThat(auditedActions(audit)).contains("DOCUMENT_VERSION_UPLOADED");
    }

    @Test
    void aDocumentThatIsAboutToExpireCanBeRenewedWithoutAWindow() {
        DocumentActor guardian = actor(guardianUser, "GUARDIAN");
        store.insertVersion(player, medical.id(), UUID.randomUUID(), TODAY.minusDays(300), TODAY.plusDays(20), null, guardianUser);

        DocumentVersionInfo renewed = service.upload(guardian, upload(medical, TODAY.plusDays(365)));

        assertThat(renewed.versionNumber()).isEqualTo(2);
    }

    @Test
    void theCoordinatorUploadsAnytimeButAnOutsiderGuardianCannotEvenSeeThePlayer() {
        service.upload(actor(coordinatorUser, "COORDINATOR"), upload(medical, TODAY.plusDays(300)));
        service.upload(actor(coordinatorUser, "COORDINATOR"), upload(medical, TODAY.plusDays(400)));
        assertThat(store.findVersions(player, medical.id())).hasSize(2);

        assertThat(codeOf(() -> service.upload(actor(outsiderGuardian, "GUARDIAN"), upload(medical, TODAY.plusDays(300)))))
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        // Quien ve al jugador pero no puede cargar recibe 403, no 404.
        assertThat(codeOf(() -> service.upload(actor(committeeUser, "COMMITTEE"), upload(medical, TODAY.plusDays(300)))))
                .isEqualTo(ErrorCode.AUTH_FORBIDDEN);
    }

    @Test
    void invalidVersionsAreRejectedWithAllTheirProblemsAndLeaveNoTrace() {
        DocumentActor guardian = actor(guardianUser, "GUARDIAN");

        // Vencido ya y sin archivo: dos problemas juntos.
        DomainException expired = thrown(() -> service.upload(guardian,
                new UploadVersion(player, medical.id(), "c.pdf", "application/pdf", new byte[0], null, TODAY.minusDays(2), null)));
        assertThat(expired.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(expired.getExtensions().get("errors").toString()).contains("expiresOn", "file");

        // El tipo solo admite PNG.
        DomainException wrongFormat = thrown(() -> service.upload(guardian,
                new UploadVersion(player, photo.id(), "foto.pdf", "application/pdf", PDF, null, null, null)));
        assertThat(wrongFormat.getExtensions().get("errors").toString()).contains("image/png");

        // Un tipo desconocido.
        assertThat(thrown(() -> service.upload(guardian, new UploadVersion(player, UUID.randomUUID(), "c.pdf", "application/pdf",
                PDF, null, null, null))).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(store.findVersions(player, medical.id())).isEmpty();
        assertThat(store.findVersions(player, photo.id())).isEmpty();
    }

    // ---------- acceso a documentos de salud ----------

    @Test
    void healthDocumentsAreOnlyForTheCoordinatorTheHeadCoachAndTheGuardian() {
        DocumentVersionInfo version = service.upload(actor(guardianUser, "GUARDIAN"), upload(medical, TODAY.plusDays(300)));

        assertThat(java.util.Arrays.equals(service.download(actor(coordinatorUser, "COORDINATOR"), player, version.id()).content(), PDF)).isTrue();
        assertThat(java.util.Arrays.equals(service.download(actor(headCoach, "COACH"), player, version.id()).content(), PDF)).isTrue();
        assertThat(java.util.Arrays.equals(service.download(actor(guardianUser, "GUARDIAN"), player, version.id()).content(), PDF)).isTrue();

        // El comité ve al jugador pero no sus documentos de salud (403); otro entrenador ni siquiera ve al jugador (404).
        assertThat(codeOf(() -> service.download(actor(committeeUser, "COMMITTEE"), player, version.id())))
                .isEqualTo(ErrorCode.AUTH_FORBIDDEN);
        assertThat(codeOf(() -> service.versions(actor(committeeUser, "COMMITTEE"), player, medical.id())))
                .isEqualTo(ErrorCode.AUTH_FORBIDDEN);
        assertThat(codeOf(() -> service.download(actor(otherCoach, "COACH"), player, version.id())))
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(codeOf(() -> service.download(actor(outsiderGuardian, "GUARDIAN"), player, version.id())))
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void everyConsultationOfAHealthDocumentIsAuditedButOtherDocumentsAreNot() {
        DocumentVersionInfo health = service.upload(actor(guardianUser, "GUARDIAN"), upload(medical, TODAY.plusDays(300)));
        DocumentVersionInfo plain = service.upload(actor(guardianUser, "GUARDIAN"),
                new UploadVersion(player, photo.id(), "foto.png", "image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 1}, null, null, null));
        audit.records.clear();

        service.download(actor(headCoach, "COACH"), player, health.id());
        service.versions(actor(coordinatorUser, "COORDINATOR"), player, medical.id());
        service.download(actor(committeeUser, "COMMITTEE"), player, plain.id());
        service.versions(actor(committeeUser, "COMMITTEE"), player, photo.id());

        assertThat(auditedActions(audit)).containsExactly("DOCUMENT_HEALTH_VIEWED", "DOCUMENT_HEALTH_VIEWED");
        assertThat(audit.records.get(0).actorId()).isEqualTo(headCoach);
        assertThat(audit.records.get(0).actorRole()).isEqualTo("COACH");
        // La evidencia nunca guarda el contenido ni el nombre del archivo.
        assertThat(audit.records.get(0).afterJson().contains("certificado.pdf")).isFalse();
    }

    @Test
    void aVersionOfAnotherPlayerIsNotReachableThroughThisPlayer() {
        UUID other = UUID.randomUUID();
        store.players.add(other);
        DocumentVersionInfo foreign = store.insertVersion(other, photo.id(), UUID.randomUUID(), null, null, null, coordinatorUser);

        assertThat(codeOf(() -> service.download(actor(coordinatorUser, "COORDINATOR"), player, foreign.id())))
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ---------- listado ----------

    @Test
    void theListShowsStatusAndWhetherTheViewerMayUploadAndHidesHealthTypesFromTheCommittee() {
        DocumentActor guardian = actor(guardianUser, "GUARDIAN");
        Map<String, PlayerDocumentService.PlayerDocument> before = byCode(service.list(guardian, player));
        assertThat(before.get("MEDICAL_CERTIFICATE").status()).isEqualTo(Status.MISSING);
        assertThat(before.get("MEDICAL_CERTIFICATE").uploadAllowed()).isTrue();

        service.upload(guardian, upload(medical, TODAY.plusDays(300)));
        Map<String, PlayerDocumentService.PlayerDocument> after = byCode(service.list(guardian, player));
        assertThat(after.get("MEDICAL_CERTIFICATE").status()).isEqualTo(Status.VALID);
        assertThat(after.get("MEDICAL_CERTIFICATE").uploadAllowed()).isFalse();
        assertThat(after.get("MEDICAL_CERTIFICATE").current().versionNumber()).isEqualTo(1);

        Map<String, PlayerDocumentService.PlayerDocument> committee = byCode(service.list(actor(committeeUser, "COMMITTEE"), player));
        assertThat(committee.keySet().contains("MEDICAL_CERTIFICATE")).isFalse();
        assertThat(committee.keySet().contains("ID_PHOTO")).isTrue();
        assertThat(committee.get("ID_PHOTO").uploadAllowed()).isFalse();

        assertThat(codeOf(() -> service.list(actor(otherCoach, "COACH"), player))).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private Map<String, PlayerDocumentService.PlayerDocument> byCode(List<PlayerDocumentService.PlayerDocument> documents) {
        Map<String, PlayerDocumentService.PlayerDocument> map = new HashMap<>();
        documents.forEach(d -> map.put(d.type().code(), d));
        return map;
    }

    private static DomainException thrown(Runnable action) {
        try {
            action.run();
        } catch (DomainException e) {
            return e;
        }
        throw new AssertionError("se esperaba una DomainException");
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

    private static final class FakeContent implements FileContentStorage {
        final Map<String, byte[]> content = new HashMap<>();

        @Override
        public void write(String key, byte[] bytes) {
            content.put(key, bytes);
        }

        @Override
        public byte[] read(String key) {
            return content.get(key);
        }
    }

    /** Almacén en memoria; el archivo de cada versión sale del {@link FakeFiles} del servicio real, por eso se enlaza tarde. */
    private final class FakeStore implements DocumentStore {
        final Map<UUID, DocumentTypeInfo> types = new java.util.LinkedHashMap<>();
        final Set<UUID> players = new HashSet<>();
        final Map<UUID, UUID> headCoaches = new HashMap<>();
        final List<Object[]> windows = new ArrayList<>();
        final List<DocumentVersionInfo> versions = new ArrayList<>();

        DocumentTypeInfo addType(String code, String name, boolean mandatory, boolean requiresExpiry, boolean health,
                                 List<String> formats) {
            DocumentTypeInfo type = new DocumentTypeInfo(UUID.randomUUID(), code, name, mandatory, requiresExpiry, null, health,
                    null, formats, true);
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
                    "certificado.pdf", "application/pdf", PDF.length, issuedOn, expiresOn, notes, uploadedBy, NOW);
            versions.add(version);
            return version;
        }

        @Override
        public boolean isFileAttached(UUID fileId) {
            return versions.stream().anyMatch(v -> v.fileId().equals(fileId));
        }

        @Override
        public void lockFile(UUID fileId) {
            // sin concurrencia en memoria
        }

        @Override
        public List<String> findMissingMandatory(UUID playerId, LocalDate onDate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ExpiringDocument> findExpiring(LocalDate from, int withinDays) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isHeadCoachOf(UUID userId, UUID playerId) {
            return userId.equals(headCoaches.get(playerId));
        }
    }
}
