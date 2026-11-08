package co.edu.unipamplona.canchatrack.players.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.players.domain.CallInfo;

@Service
@Transactional(readOnly = true)
public class CallQueryService {

    private final CallCatalog calls;

    public CallQueryService(CallCatalog calls) {
        this.calls = calls;
    }

    public List<CallInfo> listOpen() {
        return calls.findOpen();
    }
}
