package warranty.assistant.draft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * The only three tools a model gets, and all of them read. One instance serves one request and is tied
 * to the dealer group of that claim, so notes that ask for another group's claims get nothing.
 */
public class ClaimTools {
    private final ClaimReader reader;
    private final String groupCode;
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    public ClaimTools(ClaimReader reader, String groupCode) {
        this.reader = reader;
        this.groupCode = groupCode;
    }

    @Tool(description = "Read one claim by its number")
    public Object claimLookup(@ToolParam(description = "claim number") String claimNo) {
        calls.add("claimLookup " + claimNo);
        var claim = reader.claim(claimNo);
        return claim == null || !groupCode.equals(claim.groupCode()) ? "No such claim" : claim;
    }

    @Tool(description = "Read the warranty terms for a machine model")
    public Object partWarrantyTerms(@ToolParam(description = "model code") String modelCode) {
        calls.add("partWarrantyTerms " + modelCode);
        return reader.terms(modelCode);
    }

    @Tool(description = "List earlier claims for a machine serial number")
    public Object priorRepairs(@ToolParam(description = "serial number") String serialNo) {
        calls.add("priorRepairs " + serialNo);
        return reader.repairs(serialNo).stream().filter(r -> groupCode.equals(r.groupCode())).toList();
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }
}
