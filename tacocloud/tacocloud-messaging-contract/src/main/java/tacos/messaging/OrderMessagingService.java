package tacos.messaging;

import reactor.core.publisher.Mono;
import tacos.messaging.event.OrderEvent;

public interface OrderMessagingService {

  void sendOrder(OrderEvent event);

  /**
   * Envía un evento de orden de forma reactiva hacia el broker.
   * Representa la finalización real de la operación disponible en cada transporte
   * (asíncrono con confirmación del broker en Kafka, o síncrono aislado sin bloqueo
   * de hilo en RabbitMQ/JMS).
   *
   * @param event el evento de orden a enviar
   * @return Mono<Void> que emite completitud cuando la entrega finaliza, o error si falla
   */
  default Mono<Void> sendOrderReactive(OrderEvent event) {
    return Mono.fromRunnable(() -> sendOrder(event));
  }

  default void sendOrder(Object order) {
    if (order instanceof OrderEvent) {
      sendOrder((OrderEvent) order);
    }
  }

  default void sendOrderEvent(OrderEvent event) {
    sendOrder(event);
  }
}
