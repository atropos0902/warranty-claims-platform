package warranty.assistant.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import warranty.assistant.draft.DraftService;
import warranty.assistant.draft.DraftService.Draft;
import warranty.assistant.draft.Model.Claim;
import warranty.assistant.policy.Clause;

@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {
    private final DraftService drafts;

    public AssistantController(DraftService drafts) {
        this.drafts = drafts;
    }

    @PostMapping("/claims/{claimNo}/draft")
    @PreAuthorize("hasRole('ADJUSTER')")
    public Draft draft(@PathVariable String claimNo, Principal who) {
        return drafts.draft(claimNo, who.getName());
    }

    /** Same retrieval a draft uses, so the evaluation job can test other models on equal terms. */
    @GetMapping("/clauses")
    @PreAuthorize("hasRole('ADJUSTER') or hasAuthority('SCOPE_assistant/evaluate')")
    public List<Clause> clauses(@RequestParam @Size(max = 2000) String q) {
        return drafts.retrieve(q).stream().map(s -> s.clause()).toList();
    }

    @PostMapping("/evaluations/draft")
    @PreAuthorize("hasAuthority('SCOPE_assistant/evaluate')")
    public Draft evaluate(@Valid @RequestBody Sample s, Principal who) {
        return drafts.run(new Claim(s.id(), "EVAL", s.modelCode(), "EVAL", "S", s.repairCode(), "1", "100", List.of(),
                s.notes()), 0, who.getName());
    }

    public record Sample(@NotBlank @Size(max = 20) String id, String modelCode, String repairCode,
                         @NotBlank @Size(max = 2000) String notes) {}
}
