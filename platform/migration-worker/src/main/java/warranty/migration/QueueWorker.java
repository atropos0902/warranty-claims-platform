package warranty.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import warranty.migration.Events.Claim;
import warranty.migration.Events.Envelope;

/** Reads both queues. A message is deleted only after its work is done, and five failures send it to the dead letter queue. */
@Component
class QueueWorker {
    private static final Logger log = LoggerFactory.getLogger(QueueWorker.class);
    private final SqsClient sqs;
    private final QueueProperties queues;
    private final LegacyClaims legacy;
    private final RestClient pricing;
    private final ObjectMapper json;

    QueueWorker(SqsClient sqs, QueueProperties queues, LegacyClaims legacy, RestClient pricingHttp, ObjectMapper json) {
        this.sqs = sqs;
        this.queues = queues;
        this.legacy = legacy;
        this.pricing = pricingHttp;
        this.json = json;
    }

    @Scheduled(fixedDelay = 200)
    void replay() {
        drain(queues.replayUrl(), this::replayOne);
    }

    @Scheduled(fixedDelay = 200)
    void compare() {
        drain(queues.compareUrl(), e -> pricing.post().uri("/api/v1/pricing/comparisons").body(e.body()).retrieve()
                .toBodilessEntity());
    }

    void replayOne(Envelope e) throws Exception {
        Claim claim = json.treeToValue(e.body(), Claim.class);
        LegacyClaims.Price price = legacy.replay(e.eventId(), claim);
        if (price == null) {
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("claimNo", claim.claimNo());
        body.put("groupCode", claim.groupCode());
        body.put("version", claim.version());
        body.put("claim", pricingInput(claim));
        body.put("legacy", price);
        pricing.post().uri("/api/v1/pricing/comparisons").body(body).retrieve().toBodilessEntity();
    }

    private static Map<String, Object> pricingInput(Claim c) {
        Map<String, Object> m = new HashMap<>();
        m.put("dealerCode", c.dealerCode());
        m.put("modelCode", c.modelCode());
        m.put("claimType", c.claimType());
        m.put("purchaseDate", c.purchaseDate());
        m.put("failureDate", c.failureDate());
        m.put("meterHours", c.meterHours());
        m.put("repairCode", c.repairCode());
        m.put("laborHours", c.laborHours());
        m.put("travelMiles", c.travelMiles());
        m.put("lines", c.lines().stream().map(l -> Map.of("qty", l.qty(), "unitCost", l.unitCost())).toList());
        return m;
    }

    private interface Work {
        void run(Envelope e) throws Exception;
    }

    private void drain(String url, Work work) {
        try {
            List<Message> messages;
            do {
                messages = sqs.receiveMessage(r -> r.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(5)).messages();
                for (Message m : messages) {
                    try {
                        work.run(json.readValue(m.body(), Envelope.class));
                        sqs.deleteMessage(d -> d.queueUrl(url).receiptHandle(m.receiptHandle()));
                    } catch (Exception e) {
                        log.error("Message {} failed and stays on the queue", m.messageId(), e);
                    }
                }
            } while (!messages.isEmpty());
        } catch (SdkException e) {
            log.warn("Queue {} cannot be read: {}", url, e.getMessage());
        }
    }
}
