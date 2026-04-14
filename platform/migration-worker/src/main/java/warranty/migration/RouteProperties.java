package warranty.migration;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Listener rules are written as {@code GROUP=ruleArn}, one per dealer group. */
@ConfigurationProperties("warranty.routes")
public record RouteProperties(List<String> listenerRules, String legacyTargetGroup, String platformTargetGroup) {

    String ruleFor(String group) {
        return listenerRules.stream().filter(r -> r.startsWith(group + "=")).map(r -> r.substring(group.length() + 1))
                .findFirst().orElseThrow(() -> new IllegalStateException("No listener rule for " + group));
    }
}
