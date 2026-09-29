package tacos.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import tacos.messaging.event.OrderEvent;

/**
 * Adapter de mensajería NoOp (No Operation).
 *
 * Utilizado cuando no hay un broker real configurado en el entorno de ejecución.
 * Registra los eventos en log sin realizar I/O externo y emite completitud inmediata.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "tacocloud.messaging.transport", havingValue = "noop", matchIfMissing = true)
public class NoOpOrderMessagingService implements OrderMessagingService {

  @Override
  public void sendOrder(OrderEvent event) {
    log.info("NOOP: Sending order event to kitchen: {}", event != null ? event.getEventId() : null);
  }

  @Override
  public Mono<Void> sendOrderReactive(OrderEvent event) {
    return Mono.fromRunnable(() -> sendOrder(event));
  }

  @Override
  public void sendOrder(Object order) {
    if (order instanceof OrderEvent) {
      sendOrder((OrderEvent) order);
    } else {
      log.info("NOOP: Sending order to kitchen: {}", order);
    }
  }

}
