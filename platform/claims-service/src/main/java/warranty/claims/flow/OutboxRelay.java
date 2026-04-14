package warranty.claims.flow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import warranty.claims.QueueProperties;
import warranty.claims.data.Outbox;

/**
 * Sends waiting events in order. A crash after the send and before the commit sends the event again,
 * so consumers have to accept repeats.
 */
@Component
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final Outbox outbox;
    private final SqsClient sqs;
    private final QueueProperties queues;
    private final TransactionTemplate tx;

    public OutboxRelay(Outbox outbox, SqsClient sqs, QueueProperties queues, TransactionTemplate tx) {
        this.outbox = outbox;
        this.sqs = sqs;
        this.queues = queues;
        this.tx = tx;
    }

    @Scheduled(fixedDelay = 500)
    public void publish() {
        tx.executeWithoutResult(s -> {
            if (!outbox.lockRelay()) {
                return;
            }
            for (var event : outbox.waiting(100)) {
                try {
                    sqs.sendMessage(m -> m
                            .queueUrl(event.destination() == Outbox.Destination.REPLAY ? queues.replayUrl() : queues.compareUrl())
                            .messageGroupId(event.claimNo())
                            .messageDeduplicationId(event.eventId())
                            .messageBody("{\"eventId\":\"" + event.eventId() + "\",\"body\":" + event.payload() + "}"));
                } catch (SdkException e) {
                    // Stop here so a later event for the same claim cannot overtake this one.
                    log.warn("Queue send failed, will retry: {}", e.getMessage());
                    return;
                }
                outbox.published(event.id());
            }
        });
    }
}
