package warranty.assistant.draft;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import warranty.assistant.policy.ClauseIndex.Scored;
import warranty.assistant.policy.KeywordClauseIndex;

/**
 * Not a language model. It quotes the sentence that shares the most words with the notes and stays
 * silent when the overlap is thin. It keeps the whole path runnable without an account anywhere, and
 * gives the evaluation a floor that a real model has to beat.
 */
public class KeywordModel implements Model {
    private static final double ENOUGH = 7.0;
    private static final double CLEAR_LEAD = 1.15;

    @Override
    public String name() {
        return "keyword-baseline";
    }

    @Override
    public Output draft(Claim claim, List<Scored> clauses, ClaimTools tools) {
        if (clauses.isEmpty() || clauses.getFirst().score() < ENOUGH
                || (clauses.size() > 1 && clauses.getFirst().score() < CLEAR_LEAD * clauses.get(1).score())) {
            return new Output("NONE", null, null, "No clause matches the notes closely.");
        }
        var clause = clauses.getFirst().clause();
        Set<String> notes = KeywordClauseIndex.words(claim.notes()).collect(Collectors.toCollection(HashSet::new));
        String sentence = sentences(clause.text()).stream()
                .max(Comparator.comparingLong(s -> KeywordClauseIndex.words(s).distinct().filter(notes::contains).count()))
                .orElse(clause.text());
        boolean denies = sentence.contains("not covered") || sentence.contains("not paid") || sentence.contains("denied")
                || sentence.contains("out of coverage");
        return new Output(denies ? "DENY" : "APPROVE", clause.id(), sentence, "Closest match is clause " + clause.id() + ".");
    }

    static List<String> sentences(String text) {
        return List.of(text.split("(?<=\\.) (?=[A-Z])"));
    }
}
