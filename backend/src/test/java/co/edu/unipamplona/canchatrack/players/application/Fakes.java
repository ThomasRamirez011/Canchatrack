package co.edu.unipamplona.canchatrack.players.application;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.documents.api.ConsentKind;
import co.edu.unipamplona.canchatrack.documents.api.ConsentRecorder;
import co.edu.unipamplona.canchatrack.documents.api.EnrollmentDocuments;
import co.edu.unipamplona.canchatrack.identity.api.GuardianDirectory;
import co.edu.unipamplona.canchatrack.players.domain.CallInfo;
import co.edu.unipamplona.canchatrack.players.domain.CallStatus;
import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;
import co.edu.unipamplona.canchatrack.shared.domain.IdDocumentType;

/** Dobles en memoria de los puertos de players y de las fachadas de otros módulos. */
final class Fakes {

    static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private Fakes() {
    }

    static CallInfo call(CallStatus status, boolean windowOpen) {
        return new CallInfo(UUID.randomUUID(), "Convocatoria 2027", status, NOW.minusSeconds(86_400),
                NOW.plusSeconds(86_400), "Instrucciones", windowOpen);
    }

    static final class FakeCalls implements CallCatalog {

        final Map<UUID, CallInfo> calls = new LinkedHashMap<>();
        final Set<String> exceptions = new HashSet<>();

        CallInfo add(CallInfo call) {
            calls.put(call.id(), call);
            return call;
        }

        void grantException(UUID callId, UUID guardianId) {
            exceptions.add(callId + ":" + guardianId);
        }

        @Override
        public Optional<CallInfo> findById(UUID callId) {
            return Optional.ofNullable(calls.get(callId));
        }

        @Override
        public List<CallInfo> findOpen() {
            return calls.values().stream()
                    .filter(c -> c.status() == CallStatus.PUBLISHED && c.windowOpen()).toList();
        }

        @Override
        public Optional<UUID> activeExceptionId(UUID callId, UUID guardianId) {
            return exceptions.contains(callId + ":" + guardianId) ? Optional.of(UUID.nameUUIDFromBytes(
                    (callId + ":" + guardianId).getBytes())) : Optional.empty();
        }
    }

    static final class InMemoryPlayers implements PlayerStore {

        record Row(UUID id, String code, String firstName, String lastName, IdDocumentType type, String number,
                   LocalDate birthDate, PlayerStatus status, UUID createdBy) {
        }

        final Map<UUID, Row> rows = new LinkedHashMap<>();
        final Set<String> links = new HashSet<>(); // playerId:guardianId
        final Map<UUID, UUID> primaryGuardian = new HashMap<>();
        int refiled;

        Row byNumber(String number) {
            return rows.values().stream().filter(r -> r.number().equals(number)).findFirst().orElseThrow();
        }

        /** Siembra un jugador ya existente con el estado indicado y vinculado al acudiente. */
        Row seed(String number, PlayerStatus status, UUID linkedGuardian) {
            Row row = new Row(UUID.randomUUID(), "JG-" + (rows.size() + 1), "Sembrado", "Previo", IdDocumentType.TI,
                    number, LocalDate.of(2014, 5, 5), status, null);
            rows.put(row.id(), row);
            if (linkedGuardian != null) {
                links.add(row.id() + ":" + linkedGuardian);
            }
            return row;
        }

        @Override
        public Optional<PlayerRecord> findByDocumentForUpdate(IdDocumentType type, String number) {
            return rows.values().stream().filter(r -> r.type() == type && r.number().equals(number)).findFirst()
                    .map(r -> new PlayerRecord(r.id(), r.code(), r.status()));
        }

        @Override
        public PlayerRecord insert(String firstName, String lastName, IdDocumentType type, String number,
                                   LocalDate birthDate, UUID createdBy) {
            if (findByDocumentForUpdate(type, number).isPresent()) {
                throw new DuplicatePlayerDocumentException();
            }
            Row row = new Row(UUID.randomUUID(), "JG-" + (rows.size() + 1), firstName, lastName, type, number,
                    birthDate, PlayerStatus.SUBMITTED, createdBy);
            rows.put(row.id(), row);
            return new PlayerRecord(row.id(), row.code(), row.status());
        }

        @Override
        public void markRefiled(UUID playerId) {
            Row r = rows.get(playerId);
            rows.put(playerId, new Row(r.id(), r.code(), r.firstName(), r.lastName(), r.type(), r.number(),
                    r.birthDate(), PlayerStatus.SUBMITTED, r.createdBy()));
            refiled++;
        }

        @Override
        public boolean isLinkedGuardian(UUID playerId, UUID guardianId) {
            return links.contains(playerId + ":" + guardianId);
        }

        @Override
        public void linkGuardian(UUID playerId, UUID guardianId, String relationship, boolean primary, UUID createdBy) {
            links.add(playerId + ":" + guardianId);
            if (primary) {
                primaryGuardian.put(playerId, guardianId);
            }
        }

        @Override
        public Optional<PlayerDetail> findDetail(UUID playerId) {
            return Optional.ofNullable(rows.get(playerId)).map(r -> new PlayerDetail(r.id(), r.code(), r.firstName(),
                    r.lastName(), r.type().name(), r.number(), r.birthDate(), r.status(), NOW));
        }

        @Override
        public PageResult<PlayerSummary> list(PlayerFilter filter, int page, int size) {
            List<PlayerSummary> all = rows.values().stream()
                    .filter(r -> filter.status() == null || r.status() == filter.status())
                    .map(r -> new PlayerSummary(r.id(), r.code(), r.firstName(), r.lastName(), r.birthDate(), r.status()))
                    .toList();
            return new PageResult<>(all, page, size, all.size());
        }
    }

    static final class InMemoryEnrollments implements EnrollmentStore {

        record Row(UUID id, UUID playerId, UUID callId, UUID guardianId, UUID exceptionId, String filingCode) {
        }

        final List<Row> rows = new ArrayList<>();

        @Override
        public EnrollmentRecord insert(UUID playerId, UUID callId, UUID guardianId, UUID exceptionId, UUID createdBy) {
            boolean duplicate = rows.stream().anyMatch(r -> r.playerId().equals(playerId) && r.callId().equals(callId));
            if (duplicate) {
                throw new DuplicateEnrollmentException();
            }
            Row row = new Row(UUID.randomUUID(), playerId, callId, guardianId, exceptionId,
                    "RAD-" + String.format("%06d", rows.size() + 1));
            rows.add(row);
            return new EnrollmentRecord(row.id(), row.filingCode(), NOW);
        }

        @Override
        public List<EnrollmentSummary> findByPlayer(UUID playerId) {
            return rows.stream().filter(r -> r.playerId().equals(playerId))
                    .map(r -> new EnrollmentSummary(r.id(), r.filingCode(), r.callId(), "Convocatoria", NOW)).toList();
        }
    }

    static final class FakeGuardians implements GuardianDirectory {

        final Map<UUID, UUID> guardianByUser = new HashMap<>();

        UUID register(UUID userId) {
            UUID guardianId = UUID.randomUUID();
            guardianByUser.put(userId, guardianId);
            return guardianId;
        }

        @Override
        public Optional<UUID> guardianIdOf(UUID userId) {
            return Optional.ofNullable(guardianByUser.get(userId));
        }

        @Override
        public boolean exists(UUID guardianId) {
            return guardianByUser.containsValue(guardianId);
        }
    }

    static final class RecordingConsents implements ConsentRecorder {

        record Entry(UUID guardianId, UUID playerId, ConsentKind kind, String versionText) {
        }

        final List<Entry> entries = new ArrayList<>();

        @Override
        public void recordAuthenticatedAcceptance(UUID guardianId, UUID playerId, ConsentKind kind, String versionText) {
            entries.add(new Entry(guardianId, playerId, kind, versionText));
        }
    }

    /** Doble de la fachada de documentos de la radicación: registra lo adjuntado y permite forzar problemas o fallos. */
    static final class FakeDocuments implements EnrollmentDocuments {

        record Call(UUID userId, UUID playerId, List<Attachment> attachments) {
        }

        final List<Problem> problems = new ArrayList<>();
        final List<Call> attachCalls = new ArrayList<>();
        final List<UUID> checkedFor = new ArrayList<>();
        List<String> missing = List.of();
        RuntimeException attachFailure;

        @Override
        public List<Problem> check(UUID guardianUserId, List<Attachment> attachments) {
            checkedFor.add(guardianUserId);
            return List.copyOf(problems);
        }

        @Override
        public Attached attach(UUID guardianUserId, UUID playerId, List<Attachment> attachments, InetAddress ip, String userAgent) {
            if (attachFailure != null) {
                throw attachFailure;
            }
            attachCalls.add(new Call(guardianUserId, playerId, attachments));
            return new Attached(attachments.size(), missing);
        }
    }

    static final class RecordingAudit implements AuditWriter {

        final List<AuditRecord> inTransaction = new ArrayList<>();
        final List<AuditRecord> independent = new ArrayList<>();

        @Override
        public void write(AuditRecord record) {
            inTransaction.add(record);
        }

        @Override
        public void writeIndependent(AuditRecord record) {
            independent.add(record);
        }
    }
}
