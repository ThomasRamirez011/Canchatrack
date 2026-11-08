package co.edu.unipamplona.canchatrack.identity.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import co.edu.unipamplona.canchatrack.identity.api.GuardianDirectory;

@Service
public class GuardianDirectoryService implements GuardianDirectory {

    private final GuardianRegistry guardians;

    public GuardianDirectoryService(GuardianRegistry guardians) {
        this.guardians = guardians;
    }

    @Override
    public Optional<UUID> guardianIdOf(UUID userId) {
        return userId == null ? Optional.empty() : guardians.findGuardianId(userId);
    }

    @Override
    public boolean exists(UUID guardianId) {
        return guardianId != null && guardians.existsById(guardianId);
    }
}
