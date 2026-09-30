package tacos.messaging;

import javax.jms.JMSException;
import javax.jms.Message;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tacos.messaging.event.OrderEvent;

/**
 * Adapter de mensajería para JMS (ActiveMQ / Artemis).
 *
 * Semántica de transporte: JmsTemplate opera de forma bloqueante síncrona sobre la sesión JMS.
 * Para preservar la arquitectura no bloqueante en WebFlux/Reactor, se aísla la ejecución
 * en Schedulers.boundedElastic() y emite completitud tras terminar el envío síncrono.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "tacocloud.messaging.transport", havingValue = "jms")
public class JmsOrderMessagingService implements OrderMessagingService {

  private final JmsTemplate jms;

  @Value("${tacocloud.messaging.jms.destination:tacocloud.order.queue}")
  private String destination = "tacocloud.order.queue";

  @Autowired
  public JmsOrderMessagingService(JmsTemplate jms) {
    this.jms = jms;
  }

  @Override
  public void sendOrder(OrderEvent event) {
    jms.convertAndSend(destination, event, this::addOrderSource);
  }

  @Override
  public Mono<Void> sendOrderReactive(OrderEvent event) {
    return Mono.fromRunnable(() -> {
      log.debug("JMS despachando evento de orden {} a {}", event != null ? event.getEventId() : null, destination);
      sendOrder(event);
    })
    .subscribeOn(Schedulers.boundedElastic())
    .then();
  }

  @Override
  public void sendOrder(Object order) {
    if (order instanceof OrderEvent) {
      sendOrder((OrderEvent) order);
    } else {
      jms.convertAndSend(destination, order, this::addOrderSource);
    }
  }

  private Message addOrderSource(Message message) throws JMSException {
    message.setStringProperty("X_ORDER_SOURCE", "WEB");
    return message;
  }

}
