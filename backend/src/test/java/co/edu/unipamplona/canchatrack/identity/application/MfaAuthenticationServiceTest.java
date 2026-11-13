package co.edu.unipamplona.canchatrack.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.identity.application.Fakes.FakeHasher;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.FakeIssuer;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.FakeMfaCrypto;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.InMemoryMfaStore;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.InMemoryRefreshTokens;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.InMemoryUsers;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.MutableClock;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.RecordingAudit;
import co.edu.unipamplona.canchatrack.identity.application.MfaAuthenticationService.EnrollmentSecret;
import co.edu.unipamplona.canchatrack.identity.application.MfaAuthenticationService.MfaOutcome;
import co.edu.unipamplona.canchatrack.identity.application.MfaAuthenticationService.RegenerateResult;
import co.edu.unipamplona.canchatrack.identity.domain.Base32;
import co.edu.unipamplona.canchatrack.identity.domain.LockoutPolicy;
import co.edu.unipamplona.canchatrack.identity.domain.MfaStep;
import co.edu.unipamplona.canchatrack.identity.domain.RoleCode;
import co.edu.unipamplona.canchatrack.identity.domain.Totp;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

class MfaAuthenticationServiceTest {

    private static final String EMAIL = "coord@club.test";
    private static final String PASSWORD = "Coordinador-2026";

    private final MutableClock clock = new MutableClock();
    private final InMemoryUsers users = new InMemoryUsers();
    private final InMemoryRefreshTokens refreshTokens = new InMemoryRefreshTokens();
    private final InMemoryMfaStore store = new InMemoryMfaStore();
    private final FakeHasher hasher = new FakeHasher();
    private final RecordingAudit audit = new RecordingAudit();

    private AuthSettings settings;
    private AuthenticationService auth;
    private MfaAuthenticationService mfa;
    private UserAdministrationService administration;
    private UUID coordinatorId;

    /** Cuenta ya enrolada: su secreto, la sesión que abrió al terminar y los códigos de respaldo. */
    private record Enrolled(UUID userId, byte[] secret, SessionResult.Opened session, List<String> backupCodes) {
    }

    @BeforeEach
    void setUp() {
        settings = new AuthSettings(Duration.ofDays(7), Duration.ofDays(30), new LockoutPolicy(5, Duration.ofMinutes(15)),
                false);
        MfaSettings mfaSettings = new MfaSettings(true, Set.of(RoleCode.COORDINATOR, RoleCode.COMMITTEE), "CanchaTrack",
                Duration.ofMinutes(5), 1, 10);
        MfaService gate = new MfaService(store, mfaSettings);
        auth = new AuthenticationService(users, refreshTokens, hasher, new FakeIssuer(), audit, settings, gate, clock);
        mfa = new MfaAuthenticationService(store, new FakeMfaCrypto(), gate, users, refreshTokens, auth, settings, audit,
                clock);
        administration = new UserAdministrationService(users, hasher, audit);
        coordinatorId = createUser(EMAIL, "COORDINATOR");
    }

    private UUID createUser(String email, String role) {
        return administration.createStaffUser(new CreateUserCommand(email, "Persona " + email, PASSWORD, List.of(role)),
                UUID.randomUUID(), "COORDINATOR", ClientOrigin.UNKNOWN).id();
    }

    private SessionResult.MfaRequired loginChallenge(String email) {
        SessionResult result = auth.login(email, PASSWORD, null);
        assertThat(result).isInstanceOf(SessionResult.MfaRequired.class);
        return (SessionResult.MfaRequired) result;
    }

    private String codeNow(byte[] secret) {
        return Totp.code(secret, Totp.stepAt(clock.instant()));
    }

    /** Un código con forma de TOTP que no vale en ningún intervalo de la ventana. */
    private String wrongCode(byte[] secret) {
        long step = Totp.stepAt(clock.instant());
        Set<String> valid = Set.of(Totp.code(secret, step - 1), Totp.code(secret, step), Totp.code(secret, step + 1));
        for (int candidate = 0; candidate < 1_000_000; candidate += 7919) {
            String code = String.format("%06d", candidate);
            if (!valid.contains(code)) {
                return code;
            }
        }
        throw new IllegalStateException("no hay código inválido");
    }

    private Enrolled enroll(String email) {
        SessionResult.MfaRequired challenge = loginChallenge(email);
        assertThat(challenge.step()).isEqualTo(MfaStep.ENROLL);
        EnrollmentSecret secret = mfa.beginEnrollment(challenge.challengeToken(), null).orElseThrow();
        byte[] bytes = Base32.decode(secret.secret());
        MfaOutcome outcome = mfa.confirmEnrollment(challenge.challengeToken(), codeNow(bytes), null);
        assertThat(outcome).isInstanceOf(MfaOutcome.Opened.class);
        MfaOutcome.Opened opened = (MfaOutcome.Opened) outcome;
        return new Enrolled(users.byEmail(email).id(), bytes, opened.session(), opened.backupCodes());
    }

    // --- el inicio de sesión

    @Test
    void aRequiredRoleGetsAnEnrollmentChallengeInsteadOfASession() {
        SessionResult.MfaRequired challenge = loginChallenge(EMAIL);

        assertThat(challenge.step()).isEqualTo(MfaStep.ENROLL);
        assertThat(challenge.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
        assertThat(refreshTokens.size()).as("todavía no hay sesión").isZero();
        assertThat(audit.count("AUTH_LOGIN_SUCCESS")).isZero();
        assertThat(audit.count("AUTH_LOGIN_MFA_CHALLENGED")).isEqualTo(1);
        assertThat(store.challenges).as("solo se guarda la huella del desafío")
                .containsKey(OpaqueToken.sha256Hex(challenge.challengeToken()))
                .doesNotContainKey(challenge.challengeToken());
        assertThat(challenge.toString()).doesNotContain(challenge.challengeToken());
    }

    @Test
    void rolesThatDoNotRequireItLogInDirectly() {
        createUser("coach@club.test", "COACH");

        assertThat(auth.login("coach@club.test", PASSWORD, null)).isInstanceOf(SessionResult.Opened.class);
        assertThat(store.challengeCount()).isZero();
    }

    @Test
    void aWrongPasswordNeverReachesTheSecondFactor() {
        assertThat(auth.login(EMAIL, "otra-clave-1234", null)).isInstanceOf(SessionResult.Rejected.class);

        assertThat(store.challengeCount()).isZero();
    }

    // --- configurar el factor

    @Test
    void enrollmentActivatesTheFactorOpensTheSessionAndHandsOutBackupCodesOnce() {
        Enrolled enrolled = enroll(EMAIL);

        assertThat(enrolled.session().tokens().refreshToken()).isNotBlank();
        assertThat(enrolled.backupCodes()).hasSize(10).doesNotHaveDuplicates()
                .allMatch(code -> code.matches("[A-HJKMNP-Z2-9]{5}-[A-HJKMNP-Z2-9]{5}"));
        assertThat(store.factors.get(enrolled.userId()).active()).isTrue();
        assertThat(mfa.status(enrolled.userId())).isEqualTo(new MfaAuthenticationService.Status(true, true, 10));
        assertThat(audit.count("MFA_ENROLLED")).isEqualTo(1);
        assertThat(audit.count("AUTH_LOGIN_SUCCESS")).isEqualTo(1);
        String everything = audit.records.toString();
        assertThat(everything).doesNotContain(Base32.encode(enrolled.secret()));
        enrolled.backupCodes().forEach(code -> assertThat(everything).doesNotContain(code));
    }

    @Test
    void afterEnrollingTheNextLoginAsksForACode() {
        enroll(EMAIL);

        assertThat(loginChallenge(EMAIL).step()).isEqualTo(MfaStep.VERIFY);
    }

    @Test
    void aWrongEnrollmentCodeIsRejectedCountedAndTheFactorStaysPending() {
        SessionResult.MfaRequired challenge = loginChallenge(EMAIL);
        byte[] secret = Base32.decode(mfa.beginEnrollment(challenge.challengeToken(), null).orElseThrow().secret());

        MfaOutcome outcome = mfa.confirmEnrollment(challenge.challengeToken(), wrongCode(secret), null);

        assertThat(outcome).isInstanceOf(MfaOutcome.Rejected.class);
        assertThat(store.factors.get(coordinatorId).active()).isFalse();
        assertThat(users.byEmail(EMAIL).failedLoginAttempts()).isEqualTo(1);
        assertThat(audit.count("MFA_VERIFY_FAILED")).isEqualTo(1);
        assertThat(refreshTokens.size()).isZero();
    }

    @Test
    void confirmingWithoutStartingTheEnrollmentIsRejected() {
        SessionResult.MfaRequired challenge = loginChallenge(EMAIL);

        assertThat(mfa.confirmEnrollment(challenge.challengeToken(), "123456", null)).isInstanceOf(MfaOutcome.Rejected.class);
    }

    @Test
    void startingTheEnrollmentAgainReplacesThePendingSecret() {
        SessionResult.MfaRequired challenge = loginChallenge(EMAIL);
        byte[] first = Base32.decode(mfa.beginEnrollment(challenge.challengeToken(), null).orElseThrow().secret());
        byte[] second = Base32.decode(mfa.beginEnrollment(challenge.challengeToken(), null).orElseThrow().secret());

        assertThat(second).isNotEqualTo(first);
        assertThat(mfa.confirmEnrollment(challenge.challengeToken(), codeNow(first), null))
                .as("el secreto viejo ya no sirve").isInstanceOf(MfaOutcome.Rejected.class);
        assertThat(mfa.confirmEnrollment(challenge.challengeToken(), codeNow(second), null))
                .isInstanceOf(MfaOutcome.Opened.class);
    }

    @Test
    void theEnrollmentSecretIsDescribedForTheAuthenticatorApp() {
        SessionResult.MfaRequired challenge = loginChallenge(EMAIL);

        EnrollmentSecret secret = mfa.beginEnrollment(challenge.challengeToken(), null).orElseThrow();

        assertThat(secret.otpauthUri()).startsWith("otpauth://totp/CanchaTrack:").contains("secret=" + secret.secret())
                .contains("issuer=CanchaTrack").contains("digits=6").contains("period=30");
        assertThat(secret.accountName()).isEqualTo(EMAIL);
        assertThat(secret.toString()).doesNotContain(secret.secret());
        assertThat(store.factors.get(coordinatorId).sealedSecret()).as("el secreto se guarda cifrado")
                .isNotEqualTo(Base32.decode(secret.secret()));
    }

    // --- verificar el código

    @Test
    void theCurrentCodeOpensTheSessionButItCannotBeUsedTwice() {
        Enrolled enrolled = enroll(EMAIL);
        clock.advance(Duration.ofSeconds(30));
        String code = codeNow(enrolled.secret());

        SessionResult.MfaRequired first = loginChallenge(EMAIL);
        MfaOutcome ok = mfa.verify(first.challengeToken(), code, null);
        assertThat(ok).isInstanceOf(MfaOutcome.Opened.class);
        assertThat(((MfaOutcome.Opened) ok).backupCodes()).isEmpty();
        assertThat(audit.count("MFA_VERIFIED")).isEqualTo(1);

        SessionResult.MfaRequired second = loginChallenge(EMAIL);
        assertThat(mfa.verify(second.challengeToken(), code, null))
                .as("el mismo código en el mismo intervalo es una repetición").isInstanceOf(MfaOutcome.Rejected.class);
    }

    @Test
    void theCodeUsedToEnrollCannotBeReplayedEither() {
        Enrolled enrolled = enroll(EMAIL);

        SessionResult.MfaRequired challenge = loginChallenge(EMAIL);

        assertThat(mfa.verify(challenge.challengeToken(), codeNow(enrolled.secret()), null))
                .isInstanceOf(MfaOutcome.Rejected.class);
    }

    @Test
    void aSlightClockDriftIsTolerated() {
        Enrolled enrolled = enroll(EMAIL);
        clock.advance(Duration.ofSeconds(90));
        String previousStepCode = Totp.code(enrolled.secret(), Totp.stepAt(clock.instant()) - 1);

        assertThat(mfa.verify(loginChallenge(EMAIL).challengeToken(), previousStepCode, null))
                .isInstanceOf(MfaOutcome.Opened.class);
    }

    @Test
    void aBackupCodeWorksOnceEvenTypedInLowerCaseWithoutTheDash() {
        Enrolled enrolled = enroll(EMAIL);
        String typed = enrolled.backupCodes().get(0).replace("-", "").toLowerCase();

        assertThat(mfa.verify(loginChallenge(EMAIL).challengeToken(), typed, null)).isInstanceOf(MfaOutcome.Opened.class);
        assertThat(mfa.status(enrolled.userId()).backupCodesRemaining()).isEqualTo(9);

        assertThat(mfa.verify(loginChallenge(EMAIL).challengeToken(), enrolled.backupCodes().get(0), null))
                .as("un código de respaldo sirve una sola vez").isInstanceOf(MfaOutcome.Rejected.class);
        assertThat(mfa.verify(loginChallenge(EMAIL).challengeToken(), enrolled.backupCodes().get(1), null))
                .isInstanceOf(MfaOutcome.Opened.class);
    }

    @Test
    void junkInTheCodeFieldIsRejected() {
        enroll(EMAIL);

        for (String junk : new String[] {"", "   ", "abc", "12345", "1234567", "ZZZZZ-ZZZZ0", null}) {
            users.recordSuccessfulLogin(coordinatorId, clock.instant()); // el contador no debe bloquear a mitad de la prueba
            assertThat(mfa.verify(loginChallenge(EMAIL).challengeToken(), junk, null))
                    .as("código: " + junk).isInstanceOf(MfaOutcome.Rejected.class);
        }
    }

    @Test
    void fiveWrongCodesLockTheAccountAndEndTheChallenge() {
        Enrolled enrolled = enroll(EMAIL);
        clock.advance(Duration.ofSeconds(30));
        SessionResult.MfaRequired challenge = loginChallenge(EMAIL);

        for (int i = 0; i < 5; i++) {
            assertThat(mfa.verify(challenge.challengeToken(), wrongCode(enrolled.secret()), null))
                    .isInstanceOf(MfaOutcome.Rejected.class);
        }

        assertThat(users.byEmail(EMAIL).lockedUntil()).as("el código errado cuenta para el bloqueo de la cuenta").isNotNull();
        assertThat(audit.count("MFA_VERIFY_FAILED")).isEqualTo(5);
        assertThat(mfa.verify(challenge.challengeToken(), codeNow(enrolled.secret()), null))
                .as("ni el código correcto sirve con la cuenta bloqueada").isInstanceOf(MfaOutcome.Rejected.class);
        assertThat(auth.login(EMAIL, PASSWORD, null)).isInstanceOf(SessionResult.Rejected.class);
    }

    @Test
    void aSuccessfulVerificationClearsTheFailureCounter() {
        Enrolled enrolled = enroll(EMAIL);
        clock.advance(Duration.ofSeconds(30));
        SessionResult.MfaRequired challenge = loginChallenge(EMAIL);
        mfa.verify(challenge.challengeToken(), wrongCode(enrolled.secret()), null);
        assertThat(users.byEmail(EMAIL).failedLoginAttempts()).isEqualTo(1);

        assertThat(mfa.verify(challenge.challengeToken(), codeNow(enrolled.secret()), null))
                .isInstanceOf(MfaOutcome.Opened.class);

        assertThat(users.byEmail(EMAIL).failedLoginAttempts()).isZero();
    }

    @Test
    void aChallengeCannotBeUsedTwice() {
        Enrolled enrolled = enroll(EMAIL);
        clock.advance(Duration.ofSeconds(30));
        SessionResult.MfaRequired challenge = loginChallenge(EMAIL);
        assertThat(mfa.verify(challenge.challengeToken(), codeNow(enrolled.secret()), null))
                .isInstanceOf(MfaOutcome.Opened.class);

        clock.advance(Duration.ofSeconds(30));
        assertThat(mfa.verify(challenge.challengeToken(), codeNow(enrolled.secret()), null))
                .isInstanceOf(MfaOutcome.Rejected.class);
    }

    @Test
    void aNewLoginReplacesTheOpenChallenge() {
        Enrolled enrolled = enroll(EMAIL);
        clock.advance(Duration.ofSeconds(30));
        SessionResult.MfaRequired old = loginChallenge(EMAIL);
        SessionResult.MfaRequired current = loginChallenge(EMAIL);

        assertThat(mfa.verify(old.challengeToken(), codeNow(enrolled.secret()), null))
                .isInstanceOf(MfaOutcome.Rejected.class);
        assertThat(mfa.verify(current.challengeToken(), codeNow(enrolled.secret()), null))
                .isInstanceOf(MfaOutcome.Opened.class);
    }

    @Test
    void anExpiredChallengeIsRejected() {
        Enrolled enrolled = enroll(EMAIL);
        clock.advance(Duration.ofSeconds(30));
        SessionResult.MfaRequired challenge = loginChallenge(EMAIL);
        clock.advance(Duration.ofMinutes(6));

        assertThat(mfa.verify(challenge.challengeToken(), codeNow(enrolled.secret()), null))
                .isInstanceOf(MfaOutcome.Rejected.class);
    }

    @Test
    void challengesAreBoundToTheirStep() {
        // Una cuenta sin factor tiene un desafío ENROLL: no puede verificar un código.
        SessionResult.MfaRequired enrollChallenge = loginChallenge(EMAIL);
        assertThat(mfa.verify(enrollChallenge.challengeToken(), "123456", null)).isInstanceOf(MfaOutcome.Rejected.class);

        // Una cuenta con factor tiene un desafío VERIFY: no puede pedir otro secreto.
        enroll(EMAIL);
        SessionResult.MfaRequired verifyChallenge = loginChallenge(EMAIL);
        assertThat(mfa.beginEnrollment(verifyChallenge.challengeToken(), null)).isEmpty();
        assertThat(mfa.confirmEnrollment(verifyChallenge.challengeToken(), "123456", null))
                .isInstanceOf(MfaOutcome.Rejected.class);
    }

    @Test
    void unknownOrBlankChallengesAreRejectedWithoutTouchingAnyAccount() {
        assertThat(mfa.verify("no-existe", "123456", null)).isInstanceOf(MfaOutcome.Rejected.class);
        assertThat(mfa.verify(null, "123456", null)).isInstanceOf(MfaOutcome.Rejected.class);
        assertThat(mfa.verify("  ", "123456", null)).isInstanceOf(MfaOutcome.Rejected.class);
        assertThat(mfa.beginEnrollment("no-existe", null)).isEmpty();
        assertThat(users.byEmail(EMAIL).failedLoginAttempts()).isZero();
    }

    @Test
    void aDisabledAccountCannotCompleteTheChallenge() {
        Enrolled enrolled = enroll(EMAIL);
        clock.advance(Duration.ofSeconds(30));
        SessionResult.MfaRequired challenge = loginChallenge(EMAIL);
        users.setStatus(enrolled.userId(), co.edu.unipamplona.canchatrack.identity.domain.AccountStatus.DISABLED);

        assertThat(mfa.verify(challenge.challengeToken(), codeNow(enrolled.secret()), null))
                .isInstanceOf(MfaOutcome.Rejected.class);
    }

    // --- códigos de respaldo

    @Test
    void regeneratingBackupCodesNeedsAFreshCodeAndInvalidatesTheOldOnes() {
        Enrolled enrolled = enroll(EMAIL);
        clock.advance(Duration.ofSeconds(30));

        RegenerateResult result = mfa.regenerateBackupCodes(enrolled.userId(), codeNow(enrolled.secret()), null);

        assertThat(result).isInstanceOf(RegenerateResult.Regenerated.class);
        List<String> fresh = ((RegenerateResult.Regenerated) result).backupCodes();
        assertThat(fresh).hasSize(10).doesNotContainAnyElementsOf(enrolled.backupCodes());
        assertThat(audit.count("MFA_BACKUP_CODES_REGENERATED")).isEqualTo(1);
        assertThat(mfa.verify(loginChallenge(EMAIL).challengeToken(), enrolled.backupCodes().get(0), null))
                .as("los códigos anteriores ya no sirven").isInstanceOf(MfaOutcome.Rejected.class);
        assertThat(mfa.verify(loginChallenge(EMAIL).challengeToken(), fresh.get(0), null))
                .isInstanceOf(MfaOutcome.Opened.class);
    }

    @Test
    void regeneratingWithAWrongCodeCountsTowardTheLockAndKeepsTheOldCodes() {
        Enrolled enrolled = enroll(EMAIL);
        clock.advance(Duration.ofSeconds(30));

        for (int i = 0; i < 4; i++) {
            assertThat(mfa.regenerateBackupCodes(enrolled.userId(), wrongCode(enrolled.secret()), null))
                    .isInstanceOf(RegenerateResult.InvalidCode.class);
        }
        assertThat(users.byEmail(EMAIL).failedLoginAttempts()).isEqualTo(4);
        assertThat(mfa.regenerateBackupCodes(enrolled.userId(), wrongCode(enrolled.secret()), null))
                .isInstanceOf(RegenerateResult.InvalidCode.class);

        assertThat(users.byEmail(EMAIL).lockedUntil()).isNotNull();
        assertThat(mfa.regenerateBackupCodes(enrolled.userId(), codeNow(enrolled.secret()), null))
                .isInstanceOfSatisfying(RegenerateResult.Locked.class,
                        locked -> assertThat(locked.retryAfterSeconds()).isBetween(1L, 900L));
        assertThat(mfa.status(enrolled.userId()).backupCodesRemaining()).isEqualTo(10);
    }

    @Test
    void regeneratingWithoutAFactorIsNotPossible() {
        assertThat(mfa.regenerateBackupCodes(coordinatorId, "123456", null)).isInstanceOf(RegenerateResult.NotEnrolled.class);
        assertThat(mfa.regenerateBackupCodes(UUID.randomUUID(), "123456", null))
                .isInstanceOf(RegenerateResult.NotEnrolled.class);
    }

    // --- reinicio por la coordinación

    @Test
    void aCoordinatorResetsAnotherUsersFactorAndTheirSessionsEnd() {
        Enrolled victim = enroll(EMAIL);
        UUID actor = createUser("otro@club.test", "COORDINATOR");

        mfa.reset(victim.userId(), actor, "COORDINATOR", null);

        assertThat(store.factors).doesNotContainKey(victim.userId());
        assertThat(store.backupCodes).doesNotContainKey(victim.userId());
        assertThat(refreshTokens.allRevoked(victim.userId())).isTrue();
        assertThat(audit.count("MFA_RESET")).isEqualTo(1);
        assertThat(audit.records.stream().filter(r -> r.action().equals("MFA_RESET")).findFirst().orElseThrow().actorId())
                .isEqualTo(actor);
        assertThat(loginChallenge(EMAIL).step()).as("tendrá que configurarlo de nuevo").isEqualTo(MfaStep.ENROLL);
    }

    @Test
    void resetClosesTheOpenChallengesToo() {
        Enrolled victim = enroll(EMAIL);
        clock.advance(Duration.ofSeconds(30));
        SessionResult.MfaRequired pending = loginChallenge(EMAIL);

        mfa.reset(victim.userId(), createUser("otro@club.test", "COORDINATOR"), "COORDINATOR", null);

        assertThat(mfa.verify(pending.challengeToken(), codeNow(victim.secret()), null))
                .isInstanceOf(MfaOutcome.Rejected.class);
    }

    @Test
    void nobodyResetsTheirOwnFactorAndUnknownUsersAreNotFound() {
        Enrolled enrolled = enroll(EMAIL);

        assertThatThrownBy(() -> mfa.reset(enrolled.userId(), enrolled.userId(), "COORDINATOR", null))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT));
        assertThat(store.factors).containsKey(enrolled.userId());

        assertThatThrownBy(() -> mfa.reset(UUID.randomUUID(), coordinatorId, "COORDINATOR", null))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // --- sesiones existentes

    @Test
    void aSessionOpenedBeforeTheFactorWasRequiredEndsAtTheNextRefresh() {
        AuthenticationService before = new AuthenticationService(users, refreshTokens, hasher, new FakeIssuer(), audit,
                settings, MfaGate.DISABLED, clock);
        SessionResult.Opened session = (SessionResult.Opened) before.login(EMAIL, PASSWORD, null);

        assertThat(auth.refresh(session.tokens().refreshToken(), null)).isInstanceOf(SessionResult.Rejected.class);
        assertThat(audit.count("AUTH_REFRESH_DENIED")).isEqualTo(1);
        assertThat(refreshTokens.allRevoked(coordinatorId)).isTrue();
    }

    @Test
    void aSessionOpenedWithTheFactorKeepsRefreshing() {
        Enrolled enrolled = enroll(EMAIL);

        assertThat(auth.refresh(enrolled.session().tokens().refreshToken(), null)).isInstanceOf(SessionResult.Opened.class);
    }

    @Test
    void statusTellsWhetherTheFactorIsRequiredAndConfigured() {
        UUID coachId = createUser("coach@club.test", "COACH");

        assertThat(mfa.status(coordinatorId)).isEqualTo(new MfaAuthenticationService.Status(true, false, 0));
        assertThat(mfa.status(coachId)).isEqualTo(new MfaAuthenticationService.Status(false, false, 0));
        assertThat(mfa.status(enroll(EMAIL).userId()).enrolled()).isTrue();
    }
}
