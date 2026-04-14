package warranty.migration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import warranty.commons.ApiException;

/** A move takes minutes, so the request returns at once and the result is read later. */
@RestController
@RequestMapping("/api/v1/moves")
class MovesController {
    private final GroupMover mover;
    private final Map<String, Progress> moves = new ConcurrentHashMap<>();

    MovesController(GroupMover mover) {
        this.mover = mover;
    }

    enum Target { PLATFORM, LEGACY }

    record MoveRequest(@NotBlank String groupCode, @NotNull Target to) {}

    record Progress(String groupCode, Target to, String state, GroupMover.Result result, String problem) {}

    @PostMapping
    @PreAuthorize("hasRole('OPS')")
    ResponseEntity<Progress> start(@Valid @RequestBody MoveRequest r) {
        var running = new Progress(r.groupCode(), r.to(), "RUNNING", null, null);
        var now = moves.compute(r.groupCode(), (g, old) -> {
            if (old != null && old.state().equals("RUNNING")) {
                throw new ApiException(HttpStatus.CONFLICT, "MOVE_RUNNING", "That group is already being moved");
            }
            return running;
        });
        Thread.ofVirtual().start(() -> run(r));
        return ResponseEntity.accepted().body(now);
    }

    @GetMapping("/{groupCode}")
    @PreAuthorize("hasRole('OPS')")
    Progress get(@PathVariable String groupCode) {
        var p = moves.get(groupCode);
        if (p == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No move for that group since this worker started");
        }
        return p;
    }

    private void run(MoveRequest r) {
        Progress end;
        try {
            var result = r.to() == Target.PLATFORM ? mover.toPlatform(r.groupCode()) : mover.toLegacy(r.groupCode());
            end = new Progress(r.groupCode(), r.to(), "DONE", result, null);
        } catch (RuntimeException e) {
            end = new Progress(r.groupCode(), r.to(), "FAILED", null, e.getMessage());
        }
        moves.put(r.groupCode(), end);
    }
}
