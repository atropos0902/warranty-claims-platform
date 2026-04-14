package warranty.claims.flow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import warranty.claims.data.ClaimStore;
import warranty.commons.ApiException;

@Component
public class RepriceJob {
    private static final Logger log = LoggerFactory.getLogger(RepriceJob.class);
    private final ClaimStore claims;
    private final ClaimFlow flow;

    public RepriceJob(ClaimStore claims, ClaimFlow flow) {
        this.claims = claims;
        this.flow = flow;
    }

    @Scheduled(fixedDelay = 15_000)
    public void run() {
        for (String claimNo : claims.withoutPrice()) {
            try {
                flow.priceLater(claimNo);
            } catch (ApiException e) {
                log.warn("Claim {} still has no price: {}", claimNo, e.getMessage());
            }
        }
    }
}
