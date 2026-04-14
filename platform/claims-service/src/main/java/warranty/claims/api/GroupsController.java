package warranty.claims.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import warranty.claims.data.GroupStore;
import warranty.claims.data.GroupStore.Owner;
import warranty.commons.ApiException;

@RestController
@RequestMapping("/api/v1/dealer-groups")
public class GroupsController {
    private final GroupStore groups;

    public GroupsController(GroupStore groups) {
        this.groups = groups;
    }

    @GetMapping
    public List<GroupStore.Group> list() {
        return groups.all();
    }

    /** The dealers a caller may file for. */
    @GetMapping("/dealers")
    public List<GroupStore.Dealer> dealers(Authentication who) {
        return groups.dealers(Access.limit(who));
    }

    /** Changes the owner only if it is still what the caller believes it is. */
    @PostMapping("/{code}/owner")
    @PreAuthorize("hasAuthority('SCOPE_groups/move')")
    public void owner(@PathVariable String code, @Valid @RequestBody OwnerChange change) {
        if (!groups.changeOwner(code, change.from(), change.to())) {
            throw new ApiException(HttpStatus.CONFLICT, "OWNER_CHANGED", "The group is not owned by " + change.from());
        }
    }

    @GetMapping("/{code}/transfer-state")
    @PreAuthorize("hasAuthority('SCOPE_groups/move')")
    public GroupStore.TransferState transferState(@PathVariable String code) {
        return groups.transferState(code);
    }

    public record OwnerChange(@NotNull Owner from, @NotNull Owner to) {}
}
