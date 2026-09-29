package tacos.messaging;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import org.springframework.kafka.support.SendResult;
import org.springframework.util.concurrent.ListenableFuture;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import tacos.messaging.event.OrderEvent;

/**
 * Adapter de mensajería para Apache Kafka.
 *
 * Utiliza ListenableFuture de Spring Kafka para capturar la confirmación asíncrona real
 * del broker, emitiendo completitud únicamente tras la confirmación de recepción.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "tacocloud.messaging.transport", havingValue = "kafka")
public class KafkaOrderMessagingService implements OrderMessagingService {

  private final KafkaTemplate<String, Object> kafkaTemplate;

  @Value("${tacocloud.messaging.kafka.topic:tacocloud.orders.topic}")
  private String topic = "tacocloud.orders.topic";

  @Autowired
  @SuppressWarnings("unchecked")
  public KafkaOrderMessagingService(KafkaTemplate<?, ?> kafkaTemplate) {
    this.kafkaTemplate = (KafkaTemplate<String, Object>) kafkaTemplate;
  }

  @Override
  public void sendOrder(OrderEvent event) {
    kafkaTemplate.send(topic, event);
  }

  @Override
  public Mono<Void> sendOrderReactive(OrderEvent event) {
    return Mono.create(sink -> {
      try {
        ListenableFuture<SendResult<String, Object>> future = kafkaTemplate.send(topic, event);
        future.addCallback(
            result -> {
              log.debug("Kafka broker confirmó recepción del evento {} en partición {}",
                  event != null ? event.getEventId() : null,
                  result != null && result.getRecordMetadata() != null ? result.getRecordMetadata().partition() : "unknown");
              sink.success();
            },
            ex -> {
              log.error("Kafka broker rechazó o falló al recibir evento {}: {}",
                  event != null ? event.getEventId() : null, ex.getMessage());
              sink.error(ex);
            }
        );
      } catch (Throwable t) {
        sink.error(t);
      }
    });
  }

  @Override
  public void sendOrder(Object order) {
    if (order instanceof OrderEvent) {
      sendOrder((OrderEvent) order);
    } else {
      kafkaTemplate.send(topic, order);
    }
  }

}
