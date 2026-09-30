package tacos.messaging.outbox;

import java.util.Date;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import tacos.OutboxEvent;
import tacos.OutboxStatus;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.data.OutboxEventRepository;
import tacos.messaging.OrderEventMapper;
import tacos.messaging.event.OrderEvent;
import tacos.messaging.event.OrderEventType;

/**
 * Servicio de Outbox Transaccional.
 *
 * NOTA ARQUITECTÓNICA SOBRE MONGODB Y TRANSACCIONES:
 * Las transacciones multi-documento ACID en MongoDB requieren obligatoriamente un clúster
 * configurado como Replica Set (o Mongos en Sharding). En despliegues standalone (como
 * entornos de desarrollo locales o pruebas embebidas Flapdoodle sin replica set), MongoDB
 * rechaza transacciones multi-documento con 'Command failed with error 20 (IllegalOperation)'.
 *
 * Para garantizar la máxima integridad en cualquier entorno:
 * 1. Si existe 'TransactionalOperator' configurado y activo (Replica Set disponible),
 *    la escritura de TacoOrder y OutboxEvent se ejecuta dentro de una transacción reactiva atómica.
 * 2. Si no hay soporte de transacciones multi-documento (modo standalone), NO se afirma falsa atomicidad.
 *    En su lugar, se implementa el patrón de compensación reactiva: si el guardado de OutboxEvent falla
 *    tras haber persistido TacoOrder, se ejecuta inmediatamente una acción compensatoria (eliminación del
 *    pedido huérfano) y se propaga el error, impidiendo que existan pedidos sin su correspondiente evento de mensajería.
 */
@Service
@Slf4j
public class OutboxService {

  private final OrderRepository orderRepo;
  private final OutboxEventRepository outboxRepo;
  private final ObjectMapper objectMapper;
  private final ObjectProvider<TransactionalOperator> txOperatorProvider;

  @org.springframework.beans.factory.annotation.Autowired
  public OutboxService(OrderRepository orderRepo,
                       OutboxEventRepository outboxRepo,
                       ObjectMapper objectMapper,
                       ObjectProvider<TransactionalOperator> txOperatorProvider) {
    this.orderRepo = orderRepo;
    this.outboxRepo = outboxRepo;
    this.objectMapper = objectMapper;
    this.txOperatorProvider = txOperatorProvider;
  }

  public OutboxService(OrderRepository orderRepo,
                       OutboxEventRepository outboxRepo,
                       ObjectMapper objectMapper) {
    this(orderRepo, outboxRepo, objectMapper, null);
  }

  public Mono<TacoOrder> saveOrderWithOutbox(TacoOrder order, OrderEventType eventType) {
    return saveOrderWithOutbox(order, eventType, null);
  }

  public Mono<TacoOrder> saveOrderWithOutbox(TacoOrder order, OrderEventType eventType, String correlationId) {
    if (order.getId() == null) {
      order.setId(UUID.randomUUID().toString());
    }

    OrderEvent event = OrderEventMapper.toOrderEvent(order, eventType, correlationId);

    String payloadJson;
    try {
      payloadJson = objectMapper.writeValueAsString(event);
    } catch (Exception e) {
      return Mono.error(new IllegalStateException("Error serializando evento outbox", e));
    }

    OutboxEvent outboxEvent = OutboxEvent.builder()
        .id(UUID.randomUUID().toString())
        .eventId(event.getEventId())
        .correlationId(event.getCorrelationId())
        .aggregateType("ORDER")
        .aggregateId(order.getId())
        .eventType(eventType.name())
        .eventVersion("1.0")
        .payloadJson(payloadJson)
        .status(OutboxStatus.NEW)
        .attempts(0)
        .createdAt(new Date())
        .build();

    TransactionalOperator txOperator = txOperatorProvider != null ? txOperatorProvider.getIfAvailable() : null;

    if (txOperator != null) {
      // Modo transaccional multi-documento activo (requiere MongoDB Replica Set).
      return orderRepo.save(order)
          .flatMap(savedOrder -> outboxRepo.save(outboxEvent).thenReturn(savedOrder))
          .as(txOperator::transactional)
          .onErrorResume(txError -> {
            log.error("Transacción multi-documento rechazada o fallida para pedido {}: {}. Garantizando ausencia de pedido huérfano...",
                order.getId(), txError.getMessage());
            // Si MongoDB rechaza la transacción (ej. modo Standalone con error 20) o la transacción se aborta,
            // garantizamos mediante borrado compensatorio reactivo que no persista ningún pedido sin su evento outbox.
            return orderRepo.deleteById(order.getId())
                .onErrorResume(delErr -> Mono.empty())
                .then(Mono.error(txError));
          });
    }

    // Modo Standalone (sin transacciones multi-documento activadas):
    // No se afirma soporte ACID a nivel de motor. Se aplica el patrón de compensación reactiva:
    // si falla la persistencia de OutboxEvent, se elimina inmediatamente el pedido guardado.
    return orderRepo.save(order)
        .flatMap(savedOrder -> outboxRepo.save(outboxEvent)
            .thenReturn(savedOrder)
            .onErrorResume(outboxError -> {
              log.error("Fallo al guardar OutboxEvent para pedido {}. Ejecutando compensación reactiva...",
                  savedOrder.getId(), outboxError);
              return orderRepo.deleteById(savedOrder.getId())
                  .onErrorResume(delErr -> Mono.empty())
                  .then(Mono.error(new IllegalStateException(
                      "Fallo al guardar evento Outbox para pedido " + savedOrder.getId() + ". Acción compensatoria ejecutada (pedido eliminado).",
                      outboxError)));
            })
        );
  }
}
