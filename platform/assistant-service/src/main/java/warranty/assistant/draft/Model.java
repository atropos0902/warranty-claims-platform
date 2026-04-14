package warranty.assistant.draft;

import java.util.List;
import warranty.assistant.policy.ClauseIndex.Scored;

/** Proposes a decision. What it returns is a suggestion and is checked before anyone sees it. */
public interface Model {
    String name();

    Output draft(Claim claim, List<Scored> clauses, ClaimTools tools);

    /** The parts of a claim the model is shown. */
    record Claim(String claimNo, String groupCode, String modelCode, String serialNo, String claimType, String repairCode,
                 String laborHours, String meterHours, List<String> parts, String notes) {}

    record Output(String decision, String clauseId, String quote, String reason) {}
}
