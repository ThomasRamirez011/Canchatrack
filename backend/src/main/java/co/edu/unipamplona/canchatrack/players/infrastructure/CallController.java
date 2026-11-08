package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.players.application.CallQueryService;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.CallResponse;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;

@RestController
@RequestMapping(ApiPaths.V1 + "/calls")
public class CallController {

    private final CallQueryService calls;

    public CallController(CallQueryService calls) {
        this.calls = calls;
    }

    /** Convocatorias abiertas ahora mismo (cualquier usuario autenticado). */
    @GetMapping("/open")
    public List<CallResponse> open() {
        return calls.listOpen().stream().map(CallResponse::from).toList();
    }
}
