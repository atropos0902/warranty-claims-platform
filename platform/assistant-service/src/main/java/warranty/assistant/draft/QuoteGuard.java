package warranty.assistant.draft;

import java.util.List;
import java.util.Set;
import warranty.assistant.draft.Model.Output;
import warranty.assistant.policy.Clause;
import warranty.assistant.policy.ClauseIndex.Scored;
import warranty.assistant.policy.PolicyManual;

/** Decides whether a draft may be shown. It looks only at text and never asks a model. */
public final class QuoteGuard {
    private static final Set<String> DECISIONS = Set.of("APPROVE", "DENY");
    private final PolicyManual manual;

    public QuoteGuard(PolicyManual manual) {
        this.manual = manual;
    }

    /** Null when the draft may be shown, otherwise a short code for the reason it was held back. */
    public String problem(Output out, List<Scored> shown) {
        if (out.decision() == null || out.decision().equals("NONE")) {
            return "NO_CLAUSE";
        }
        if (!DECISIONS.contains(out.decision())) {
            return "BAD_DECISION";
        }
        Clause clause = manual.get(out.clauseId());
        if (clause == null) {
            return "UNKNOWN_CLAUSE";
        }
        if (shown.stream().noneMatch(s -> s.clause().id().equals(clause.id()))) {
            return "CLAUSE_NOT_SUPPLIED";
        }
        // Whole sentences only. Half a sentence can be an exact match and still say the opposite.
        if (out.quote() == null || !wholeSentences(out.quote(), clause.text())) {
            return "QUOTE_MISMATCH";
        }
        return null;
    }

    static boolean wholeSentences(String quote, String text) {
        List<String> parts = KeywordModel.sentences(text);
        for (int from = 0; from < parts.size(); from++) {
            for (int to = from + 1; to <= parts.size(); to++) {
                if (String.join(" ", parts.subList(from, to)).equals(quote)) {
                    return true;
                }
            }
        }
        return false;
    }
}
