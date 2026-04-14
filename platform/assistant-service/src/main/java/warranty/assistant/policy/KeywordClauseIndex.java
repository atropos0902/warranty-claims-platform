package warranty.assistant.policy;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** BM25 over the manual, in memory. Used on a laptop and as the fallback when search is down. */
public class KeywordClauseIndex implements ClauseIndex {
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final Set<String> STOP = Set.of("the", "a", "an", "and", "or", "of", "to", "in", "on", "is", "was", "were",
            "are", "be", "it", "its", "for", "with", "at", "by", "from", "as", "that", "this", "when", "not", "no", "we", "had",
            "has", "have", "after", "before", "but", "they", "their", "our", "which", "than", "then", "so", "because");
    private final PolicyManual manual;
    private final Map<String, Map<String, Integer>> counts = new HashMap<>();
    private final Map<String, Integer> documents = new HashMap<>();
    private final double averageLength;

    public KeywordClauseIndex(PolicyManual manual) {
        this.manual = manual;
        double total = 0;
        for (Clause c : manual.clauses()) {
            Map<String, Integer> terms = new HashMap<>();
            Stream.concat(words(c.title()), words(c.text())).forEach(w -> terms.merge(w, 1, Integer::sum));
            counts.put(c.id(), terms);
            terms.keySet().forEach(w -> documents.merge(w, 1, Integer::sum));
            total += length(terms);
        }
        averageLength = total / manual.clauses().size();
    }

    @Override
    public List<Scored> search(String query, int limit) {
        List<String> terms = words(query).distinct().toList();
        return manual.clauses().stream().map(c -> new Scored(c, score(c, terms)))
                .filter(s -> s.score() > 0)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(limit).toList();
    }

    private double score(Clause clause, List<String> terms) {
        Map<String, Integer> has = counts.get(clause.id());
        double lengthNorm = K1 * (1 - B + B * length(has) / averageLength);
        double sum = 0;
        for (String t : terms) {
            int f = has.getOrDefault(t, 0);
            if (f > 0) {
                int n = documents.get(t);
                double idf = Math.log(1 + (manual.clauses().size() - n + 0.5) / (n + 0.5));
                sum += idf * f * (K1 + 1) / (f + lengthNorm);
            }
        }
        return sum;
    }

    private static int length(Map<String, Integer> terms) {
        return terms.values().stream().mapToInt(Integer::intValue).sum();
    }

    public static Stream<String> words(String text) {
        return Arrays.stream(text.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+"))
                .filter(w -> w.length() > 1 && !STOP.contains(w)).map(KeywordClauseIndex::stem);
    }

    /** Crude, and enough to let "hoses" meet "hose" and "leaking" meet "leaked". */
    private static String stem(String w) {
        for (String end : new String[] {"ing", "ed", "es", "s"}) {
            if (w.length() > end.length() + 3 && w.endsWith(end)) {
                return w.substring(0, w.length() - end.length()).replaceAll("e$", "");
            }
        }
        return w.replaceAll("e$", "");
    }
}
