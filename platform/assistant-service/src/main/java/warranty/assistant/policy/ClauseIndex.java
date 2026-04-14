package warranty.assistant.policy;

import java.util.List;

/** Finds clauses that may decide a claim. */
public interface ClauseIndex {
    List<Scored> search(String query, int limit);

    record Scored(Clause clause, double score) {}
}
