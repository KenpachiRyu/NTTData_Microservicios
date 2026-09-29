package tacos.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.OutboxEvent;
import tacos.OutboxStatus;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.data.OutboxEventRepository;
import tacos.messaging.event.OrderEvent;
import tacos.messaging.event.OrderEventType;
import tacos.messaging.outbox.OutboxDispatcher;
import tacos.messaging.outbox.OutboxService;

@ExtendWith(MockitoExtension.class)
public class TransactionalOutboxTest {

  @Mock
  private OrderRepository orderRepo;

  @Mock
  private OutboxEventRepository outboxRepo;

  @Mock
  private ReactiveMongoTemplate mongoTemplate;

  @Mock
  private OrderMessagingService messagingService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private OutboxService outboxService;
  private OutboxDispatcher outboxDispatcher;

  @BeforeEach
  void setUp() {
    outboxService = new OutboxService(orderRepo, outboxRepo, objectMapper);
    outboxDispatcher = new OutboxDispatcher(mongoTemplate, outboxRepo, messagingService, objectMapper);
  }

  @Test
  @DisplayName("Guardar orden registra el documento de orden y el evento outbox con estado NEW")
  void testSaveOrderWithOutbox() {
    TacoOrder order = new TacoOrder();
    order.setId("order-outbox-1");
    order.setDeliveryName("Cliente Outbox");

    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(outboxRepo.save(any(OutboxEvent.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    TacoOrder saved = outboxService.saveOrderWithOutbox(order, OrderEventType.ORDER_CREATED).block();

    assertThat(saved).isNotNull();
    assertThat(saved.getId()).isEqualTo("order-outbox-1");

    verify(outboxRepo, times(1)).save(any(OutboxEvent.class));
  }

  @Test
  @DisplayName("Fallo al guardar el evento outbox tras guardar el pedido dispara compensación reactiva (eliminación del pedido)")
  void testOutboxFailureTriggersCompensatingRollback() {
    TacoOrder order = new TacoOrder();
    order.setId("order-fail-outbox");
    order.setDeliveryName("Cliente Compensación");

    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(outboxRepo.save(any(OutboxEvent.class))).thenReturn(Mono.error(new RuntimeException("Simulated Outbox DB connection failure")));
    when(orderRepo.deleteById(eq("order-fail-outbox"))).thenReturn(Mono.empty());

    StepVerifier.create(outboxService.saveOrderWithOutbox(order, OrderEventType.ORDER_CREATED))
        .expectErrorMatches(throwable -> throwable instanceof IllegalStateException
            && throwable.getMessage().toLowerCase().contains("compensatoria"))
        .verify();

    // Verificamos que se ejecutó la acción compensatoria para no dejar pedidos huérfanos
    verify(orderRepo, times(1)).deleteById("order-fail-outbox");
  }

  @Test
  @DisplayName("Claim atómico con findAndModify marca evento en estado PUBLISHING con lease y lockedBy")
  void testClaimNextPendingEvent() {
    OutboxEvent event = OutboxEvent.builder()
        .id("evt-1")
        .aggregateId("order-1")
        .createdAt(new Date())
        .status(OutboxStatus.PUBLISHING)
        .lockedBy(outboxDispatcher.getDispatcherId())
        .leaseExpiresAt(new Date(System.currentTimeMillis() + 30000))
        .build();

    when(mongoTemplate.find(any(Query.class), eq(OutboxEvent.class))).thenReturn(Flux.empty());
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(OutboxEvent.class)))
        .thenReturn(Mono.just(event));
    when(mongoTemplate.exists(any(Query.class), eq(OutboxEvent.class))).thenReturn(Mono.just(false));

    OutboxEvent claimed = outboxDispatcher.claimNextPendingEvent().block();

    assertThat(claimed).isNotNull();
    assertThat(claimed.getStatus()).isEqualTo(OutboxStatus.PUBLISHING);
    assertThat(claimed.getLockedBy()).isEqualTo(outboxDispatcher.getDispatcherId());
    assertThat(claimed.getLeaseExpiresAt()).isNotNull();
  }

  @Test
  @DisplayName("Recuperación de evento abandonado en PUBLISHING cuyo lease expiró")
  void testClaimAbandonedPublishingEvent() {
    OutboxEvent recoveredEvent = OutboxEvent.builder()
        .id("evt-abandoned")
        .aggregateId("order-abandoned")
        .createdAt(new Date())
        .status(OutboxStatus.PUBLISHING)
        .lockedBy(outboxDispatcher.getDispatcherId())
        .leaseExpiresAt(new Date(System.currentTimeMillis() + 30000))
        .build();

    when(mongoTemplate.find(any(Query.class), eq(OutboxEvent.class))).thenReturn(Flux.empty());
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(OutboxEvent.class)))
        .thenReturn(Mono.just(recoveredEvent));
    when(mongoTemplate.exists(any(Query.class), eq(OutboxEvent.class))).thenReturn(Mono.just(false));

    OutboxEvent claimed = outboxDispatcher.claimNextPendingEvent().block();

    assertThat(claimed).isNotNull();
    assertThat(claimed.getId()).isEqualTo("evt-abandoned");
    assertThat(claimed.getStatus()).isEqualTo(OutboxStatus.PUBLISHING);
  }

  @Test
  @DisplayName("Dos dispatchers concurrentes intentando reclamar el mismo evento: sólo uno lo adquiere")
  void testConcurrentDispatchersCannotClaimSameEvent() {
    OutboxDispatcher dispatcher1 = new OutboxDispatcher(mongoTemplate, outboxRepo, messagingService, objectMapper);
    OutboxDispatcher dispatcher2 = new OutboxDispatcher(mongoTemplate, outboxRepo, messagingService, objectMapper);

    when(mongoTemplate.find(any(Query.class), eq(OutboxEvent.class))).thenReturn(Flux.empty());
    when(mongoTemplate.exists(any(Query.class), eq(OutboxEvent.class))).thenReturn(Mono.just(false));

    AtomicBoolean alreadyClaimed = new AtomicBoolean(false);

    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(OutboxEvent.class)))
        .thenAnswer(inv -> {
          if (alreadyClaimed.compareAndSet(false, true)) {
            return Mono.just(OutboxEvent.builder()
                .id("evt-exclusive")
                .aggregateId("order-ex")
                .createdAt(new Date())
                .status(OutboxStatus.PUBLISHING)
                .lockedBy(dispatcher1.getDispatcherId())
                .build());
          } else {
            return Mono.empty();
          }
        });

    OutboxEvent claim1 = dispatcher1.claimNextPendingEvent().block();
    OutboxEvent claim2 = dispatcher2.claimNextPendingEvent().block();

    assertThat(claim1).isNotNull();
    assertThat(claim1.getId()).isEqualTo("evt-exclusive");
    assertThat(claim2).isNull();
  }

  @Test
  @DisplayName("Dispatcher que pierde el lease por timeout no puede sobrescribir el estado de un nuevo owner")
  void testDispatcherLosingLeaseCannotOverwriteNewOwner() throws Exception {
    String originalDispatcherId = "dispatcher-A";
    outboxDispatcher.setDispatcherId(originalDispatcherId);

    OrderEvent domainEvent = OrderEvent.builder()
        .eventId(UUID.randomUUID().toString())
        .eventType(OrderEventType.ORDER_CREATED)
        .build();

    OutboxEvent outboxEvent = OutboxEvent.builder()
        .id("evt-lease-lost")
        .status(OutboxStatus.PUBLISHING)
        .payloadJson(objectMapper.writeValueAsString(domainEvent))
        .attempts(0)
        .lockedBy(originalDispatcherId)
        .leaseExpiresAt(new Date(System.currentTimeMillis() - 1000)) // Ya expiró
        .build();

    when(messagingService.sendOrderReactive(any(OrderEvent.class))).thenReturn(Mono.empty());

    // Simulamos que en MongoDB lockedBy ya no coincide (Dispatcher B se convirtió en owner)
    // por lo tanto la query condicional (id == evt && lockedBy == dispatcher-A) retorna vacío.
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(OutboxEvent.class)))
        .thenReturn(Mono.empty());

    OutboxEvent result = outboxDispatcher.dispatch(outboxEvent).block();

    // Como perdió ownership, el dispatcher A no actualiza la BD y retorna empty
    assertThat(result).isNull();
    verify(outboxRepo, never()).save(any(OutboxEvent.class));
  }

  @Test
  @DisplayName("Si el transporte de mensajería no está disponible (messagingService == null), no se marca PUBLISHED y queda en NEW")
  void testDispatchWhenTransportUnavailableKeepsEventNew() throws Exception {
    OutboxDispatcher noTransportDispatcher = new OutboxDispatcher(mongoTemplate, outboxRepo, null, objectMapper);

    OrderEvent domainEvent = OrderEvent.builder()
        .eventId(UUID.randomUUID().toString())
        .eventType(OrderEventType.ORDER_CREATED)
        .build();

    OutboxEvent outboxEvent = OutboxEvent.builder()
        .id("evt-no-transport")
        .status(OutboxStatus.PUBLISHING)
        .payloadJson(objectMapper.writeValueAsString(domainEvent))
        .lockedBy(noTransportDispatcher.getDispatcherId())
        .leaseExpiresAt(new Date(System.currentTimeMillis() + 30000))
        .build();

    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(OutboxEvent.class)))
        .thenAnswer(inv -> {
          outboxEvent.setStatus(OutboxStatus.NEW);
          outboxEvent.setLockedBy(null);
          outboxEvent.setLeaseExpiresAt(null);
          outboxEvent.setErrorMessage("Transporte de mensajería no disponible");
          return Mono.just(outboxEvent);
        });

    OutboxEvent result = noTransportDispatcher.dispatch(outboxEvent).block();

    assertThat(result).isNotNull();
    assertThat(result.getStatus()).isEqualTo(OutboxStatus.NEW); // NUNCA PUBLISHED
    assertThat(result.getLockedBy()).isNull();
    assertThat(result.getLeaseExpiresAt()).isNull();
    assertThat(result.getErrorMessage()).contains("no disponible");
  }

  @Test
  @DisplayName("Dispatch exitoso publica al broker reactivamente y actualiza estado a PUBLISHED liberando lease")
  void testDispatchSuccess() throws Exception {
    OrderEvent domainEvent = OrderEvent.builder()
        .eventId(UUID.randomUUID().toString())
        .eventType(OrderEventType.ORDER_CREATED)
        .build();

    OutboxEvent outboxEvent = OutboxEvent.builder()
        .id("evt-success")
        .status(OutboxStatus.PUBLISHING)
        .payloadJson(objectMapper.writeValueAsString(domainEvent))
        .attempts(0)
        .lockedBy(outboxDispatcher.getDispatcherId())
        .leaseExpiresAt(new Date(System.currentTimeMillis() + 30000))
        .build();

    when(messagingService.sendOrderReactive(any(OrderEvent.class))).thenReturn(Mono.empty());

    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(OutboxEvent.class)))
        .thenAnswer(inv -> {
          outboxEvent.setStatus(OutboxStatus.PUBLISHED);
          outboxEvent.setLastAttemptAt(new Date());
          outboxEvent.setLockedBy(null);
          outboxEvent.setLeaseExpiresAt(null);
          outboxEvent.setErrorMessage(null);
          return Mono.just(outboxEvent);
        });

    OutboxEvent result = outboxDispatcher.dispatch(outboxEvent).block();

    assertThat(result).isNotNull();
    assertThat(result.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    assertThat(result.getLastAttemptAt()).isNotNull();
    assertThat(result.getErrorMessage()).isNull();
    assertThat(result.getLockedBy()).isNull();
    assertThat(result.getLeaseExpiresAt()).isNull();
  }

  @Test
  @DisplayName("Fallo del broker incrementa intentos y deja el evento reintentable (NEW)")
  void testBrokerFailureKeepsEventRetriable() throws Exception {
    OrderEvent domainEvent = OrderEvent.builder()
        .eventId(UUID.randomUUID().toString())
        .eventType(OrderEventType.ORDER_CREATED)
        .build();

    OutboxEvent outboxEvent = OutboxEvent.builder()
        .id("evt-retry")
        .status(OutboxStatus.PUBLISHING)
        .payloadJson(objectMapper.writeValueAsString(domainEvent))
        .attempts(0)
        .lockedBy(outboxDispatcher.getDispatcherId())
        .build();

    when(messagingService.sendOrderReactive(any(OrderEvent.class)))
        .thenReturn(Mono.error(new RuntimeException("Broker connection refused")));

    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(OutboxEvent.class)))
        .thenAnswer(inv -> {
          outboxEvent.setStatus(OutboxStatus.NEW);
          outboxEvent.setAttempts(1);
          outboxEvent.setErrorMessage("Broker connection refused");
          outboxEvent.setLockedBy(null);
          outboxEvent.setLeaseExpiresAt(null);
          return Mono.just(outboxEvent);
        });

    OutboxEvent result = outboxDispatcher.dispatch(outboxEvent).block();

    assertThat(result).isNotNull();
    assertThat(result.getStatus()).isEqualTo(OutboxStatus.NEW);
    assertThat(result.getAttempts()).isEqualTo(1);
    assertThat(result.getErrorMessage()).contains("Broker connection refused");
    assertThat(result.getLockedBy()).isNull();
    assertThat(result.getLeaseExpiresAt()).isNull();
  }

  @Test
  @DisplayName("Fallo persistente agota reintentos y marca el evento como FAILED")
  void testExhaustedRetriesMarksEventAsFailed() throws Exception {
    OrderEvent domainEvent = OrderEvent.builder()
        .eventId(UUID.randomUUID().toString())
        .eventType(OrderEventType.ORDER_CREATED)
        .build();

    OutboxEvent outboxEvent = OutboxEvent.builder()
        .id("evt-failed")
        .status(OutboxStatus.PUBLISHING)
        .payloadJson(objectMapper.writeValueAsString(domainEvent))
        .attempts(2) // Ya tuvo 2 intentos; maxAttempts = 3
        .lockedBy(outboxDispatcher.getDispatcherId())
        .build();

    when(messagingService.sendOrderReactive(any(OrderEvent.class)))
        .thenReturn(Mono.error(new RuntimeException("Fatal broker error")));

    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(OutboxEvent.class)))
        .thenAnswer(inv -> {
          outboxEvent.setStatus(OutboxStatus.FAILED);
          outboxEvent.setAttempts(3);
          outboxEvent.setErrorMessage("Fatal broker error");
          outboxEvent.setLockedBy(null);
          outboxEvent.setLeaseExpiresAt(null);
          return Mono.just(outboxEvent);
        });

    OutboxEvent result = outboxDispatcher.dispatch(outboxEvent).block();

    assertThat(result).isNotNull();
    assertThat(result.getStatus()).isEqualTo(OutboxStatus.FAILED);
    assertThat(result.getAttempts()).isEqualTo(3);
    assertThat(result.getErrorMessage()).contains("Fatal broker error");
    assertThat(result.getLockedBy()).isNull();
    assertThat(result.getLeaseExpiresAt()).isNull();
  }

  @Test
  @DisplayName("Preservación estricta de orden por aggregateId: si existe evento anterior no publicado, se libera el evento más reciente")
  void testPreservesFifoOrderPerAggregate() {
    Date t1 = new Date(1000);
    Date t2 = new Date(2000);

    OutboxEvent event2 = OutboxEvent.builder()
        .id("evt-2")
        .aggregateId("order-aggregate-1")
        .createdAt(t2)
        .status(OutboxStatus.PUBLISHING)
        .lockedBy(outboxDispatcher.getDispatcherId())
        .build();

    // No hay eventos en vuelo activos
    when(mongoTemplate.find(any(Query.class), eq(OutboxEvent.class))).thenReturn(Flux.empty());
    // Se reclama preliminarmente evt-2
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(OutboxEvent.class)))
        .thenReturn(Mono.just(event2));
    // Pero la verificación secundaria detecta que existe un evento previo (evt-1 en t1) que aún no está PUBLISHED
    when(mongoTemplate.exists(any(Query.class), eq(OutboxEvent.class))).thenReturn(Mono.just(true));

    // Release update
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(OutboxEvent.class)))
        .thenReturn(Mono.empty());

    OutboxEvent claimed = outboxDispatcher.claimNextPendingEvent().block();

    // El evento 2 no debe ser despachado, debe ser liberado para mantener FIFO
    assertThat(claimed).isNull();
  }
}
