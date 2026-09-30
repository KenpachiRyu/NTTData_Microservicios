package tacos.messaging;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tacos.messaging.event.OrderEvent;

/**
 * Adapter de mensajería para RabbitMQ.
 *
 * Semántica de transporte: RabbitTemplate ejecuta un envío síncrono sobre el socket AMQP.
 * Para no bloquear los hilos reactivos de Reactor/WebFlux, el envío se ejecuta aislado
 * en Schedulers.boundedElastic() y emite completitud tras terminar la llamada de RabbitTemplate.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "tacocloud.messaging.transport", havingValue = "rabbit")
public class RabbitOrderMessagingService implements OrderMessagingService {

  private final RabbitTemplate rabbit;

  @Value("${tacocloud.messaging.rabbit.destination:tacocloud.order.queue}")
  private String destination = "tacocloud.order.queue";

  @Autowired
  public RabbitOrderMessagingService(RabbitTemplate rabbit) {
    this.rabbit = rabbit;
  }

  @Override
  public void sendOrder(OrderEvent event) {
    rabbit.convertAndSend(destination, event, createPostProcessor());
  }

  @Override
  public Mono<Void> sendOrderReactive(OrderEvent event) {
    return Mono.fromRunnable(() -> {
      log.debug("RabbitMQ despachando evento de orden {} a {}", event != null ? event.getEventId() : null, destination);
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
      rabbit.convertAndSend(destination, order, createPostProcessor());
    }
  }

  private MessagePostProcessor createPostProcessor() {
    return new MessagePostProcessor() {
      @Override
      public Message postProcessMessage(Message message) throws AmqpException {
        MessageProperties props = message.getMessageProperties();
        props.setHeader("X_ORDER_SOURCE", "WEB");
        return message;
      }
    };
  }

}
