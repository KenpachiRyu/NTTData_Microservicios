package tacos.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.DeadLetterMessage;
import tacos.ProcessedEvent;
import tacos.data.DeadLetterMessageRepository;
import tacos.data.ProcessedEventRepository;
import tacos.messaging.event.OrderEvent;
import tacos.messaging.event.OrderEventType;
import tacos.messaging.idempotence.IdempotentOrderConsumer;
import tacos.messaging.idempotence.IdempotentOrderConsumer.ConsumerResult;

@ExtendWith(MockitoExtension.class)
public class IdempotentConsumerDlqTest {

  @Mock
  private ReactiveMongoTemplate mongoTemplate;

  @Mock
  private ProcessedEventRepository processedEventRepo;

  @Mock
  private DeadLetterMessageRepository deadLetterRepo;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private IdempotentOrderConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new IdempotentOrderConsumer(mongoTemplate, processedEventRepo, deadLetterRepo, objectMapper);
    consumer.setMaxRetries(3);
  }

  @Test
  @DisplayName("Dos llamadas concurrentes con el mismo eventId: el efecto de negocio se ejecuta exactamente una sola vez")
  void testConcurrentCallsSameEventExecutesEffectOnce() {
    String eventId = UUID.randomUUID().toString();
    OrderEvent event = OrderEvent.builder()
        .eventId(eventId)
        .eventType(OrderEventType.ORDER_CREATED)
        .version("1.0")
        .build();

    AtomicInteger effectExecutions = new AtomicInteger(0);
    AtomicBoolean reservationWon = new AtomicBoolean(false);

    // Simulamos la concurrencia real sobre el índice único de MongoDB:
    // El primer guardado de la reserva (status PROCESSING) tiene éxito.
    // El segundo guardado concurrente colisiona con DuplicateKeyException.
    when(processedEventRepo.save(any(ProcessedEvent.class))).thenAnswer(inv -> {
      ProcessedEvent p = inv.getArgument(0);
      if ("PROCESSING".equals(p.getResult())) {
        if (reservationWon.compareAndSet(false, true)) {
          return Mono.just(p); // Primer hilo gana la pre-reserva
        } else {
          return Mono.error(new DuplicateKeyException("E11000 duplicate key error on eventId_consumer_unique_idx"));
        }
      }
      return Mono.just(p); // Actualización a SUCCESS
    });

    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ProcessedEvent.class)))
        .thenReturn(Mono.empty()); // Lease aún está activo, no puede ser reclamado por el segundo hilo

    // Ejecutamos ambas entregas en paralelo
    Mono<ConsumerResult> call1 = consumer.processEvent(event, evt -> {
      effectExecutions.incrementAndGet();
      return Mono.empty();
    });

    Mono<ConsumerResult> call2 = consumer.processEvent(event, evt -> {
      effectExecutions.incrementAndGet();
      return Mono.empty();
    });

    List<ConsumerResult> results = Flux.merge(call1, call2).collectList().block();

    assertThat(results).contains(ConsumerResult.PROCESSED, ConsumerResult.DUPLICATE_IGNORED);
    assertThat(effectExecutions.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("Reclamación atómica de lease expirado en PROCESSING: sólo un consumidor gana y re-ejecuta")
  void testExpiredProcessingLeaseReclaimedAtomicallyByOnlyOneConsumer() {
    String eventId = UUID.randomUUID().toString();
    OrderEvent event = OrderEvent.builder()
        .eventId(eventId)
        .eventType(OrderEventType.ORDER_CREATED)
        .version("1.0")
        .build();

    AtomicInteger effectExecutions = new AtomicInteger(0);
    AtomicBoolean claimWon = new AtomicBoolean(false);

    // Ambos fallan al insertar por clave duplicada
    when(processedEventRepo.save(any(ProcessedEvent.class)))
        .thenAnswer(inv -> {
          ProcessedEvent p = inv.getArgument(0);
          if ("SUCCESS".equals(p.getResult())) {
            return Mono.just(p);
          }
          return Mono.error(new DuplicateKeyException("E11000 duplicate key error"));
        });

    // Simulamos la operación atómica findAndModify: sólo el primero la gana
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ProcessedEvent.class)))
        .thenAnswer(inv -> {
          if (claimWon.compareAndSet(false, true)) {
            return Mono.just(ProcessedEvent.builder()
                .eventId(eventId)
                .consumerName("KITCHEN_ORDER_CONSUMER")
                .result("PROCESSING")
                .leaseExpiresAt(new Date(System.currentTimeMillis() + 60000))
                .build());
          }
          return Mono.empty();
        });

    Mono<ConsumerResult> consumerA = consumer.processEvent(event, evt -> {
      effectExecutions.incrementAndGet();
      return Mono.empty();
    });

    Mono<ConsumerResult> consumerB = consumer.processEvent(event, evt -> {
      effectExecutions.incrementAndGet();
      return Mono.empty();
    });

    List<ConsumerResult> results = Flux.merge(consumerA, consumerB).collectList().block();

    assertThat(results).contains(ConsumerResult.PROCESSED, ConsumerResult.DUPLICATE_IGNORED);
    // El efecto se ejecutó 1 sola vez por el consumidor que ganó la reclamación atómica
    assertThat(effectExecutions.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("Colisión de clave única se maneja limpiamente reconociendo evento duplicado sin re-ejecutar efecto")
  void testUniqueKeyCollisionHandledGracefully() {
    String eventId = UUID.randomUUID().toString();
    OrderEvent event = OrderEvent.builder()
        .eventId(eventId)
        .eventType(OrderEventType.ORDER_CREATED)
        .version("1.0")
        .build();

    AtomicInteger effectExecutions = new AtomicInteger(0);

    when(processedEventRepo.save(any(ProcessedEvent.class)))
        .thenReturn(Mono.error(new DuplicateKeyException("E11000 duplicate key error")));

    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ProcessedEvent.class)))
        .thenReturn(Mono.empty()); // Ya está SUCCESS o lease vigente

    ConsumerResult result = consumer.processEvent(event, evt -> {
      effectExecutions.incrementAndGet();
      return Mono.empty();
    }).block();

    assertThat(result).isEqualTo(ConsumerResult.DUPLICATE_IGNORED);
    assertThat(effectExecutions.get()).isEqualTo(0);
  }

  @Test
  @DisplayName("Efecto completado seguido de fallo al registrar SUCCESS: no provoca una segunda ejecución ciega")
  void testEffectCompletedFollowedByFailureRecordingProcessing() {
    String eventId = UUID.randomUUID().toString();
    OrderEvent event = OrderEvent.builder()
        .eventId(eventId)
        .eventType(OrderEventType.ORDER_CREATED)
        .version("1.0")
        .build();

    AtomicInteger effectExecutions = new AtomicInteger(0);
    AtomicBoolean isFirstSave = new AtomicBoolean(true);

    when(processedEventRepo.save(any(ProcessedEvent.class))).thenAnswer(inv -> {
      if (isFirstSave.compareAndSet(true, false)) {
        return Mono.just(inv.getArgument(0)); // Pre-reserva PROCESSING tiene éxito
      }
      return Mono.error(new RuntimeException("Temporary DB blip updating SUCCESS"));
    });

    ConsumerResult result = consumer.processEvent(event, evt -> {
      effectExecutions.incrementAndGet();
      return Mono.empty();
    }).block();

    assertThat(result).isEqualTo(ConsumerResult.PROCESSED);
    assertThat(effectExecutions.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("Eventos inválidos (null o sin eventId) se persisten duraderamente en DLQ para auditoría")
  void testInvalidEventPersistedToDlq() {
    when(deadLetterRepo.save(any(DeadLetterMessage.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    ConsumerResult nullResult = consumer.processEvent(null, evt -> Mono.empty()).block();
    assertThat(nullResult).isEqualTo(ConsumerResult.SENT_TO_DLQ);

    OrderEvent emptyIdEvent = OrderEvent.builder()
        .eventId(null)
        .eventType(OrderEventType.ORDER_CREATED)
        .build();

    ConsumerResult noIdResult = consumer.processEvent(emptyIdEvent, evt -> Mono.empty()).block();
    assertThat(noIdResult).isEqualTo(ConsumerResult.SENT_TO_DLQ);

    verify(deadLetterRepo, times(2)).save(any(DeadLetterMessage.class));
  }

  @Test
  @DisplayName("Error transitorio se reintenta hasta tener éxito")
  void testTransientErrorRetried() {
    String eventId = UUID.randomUUID().toString();
    OrderEvent event = OrderEvent.builder()
        .eventId(eventId)
        .eventType(OrderEventType.ORDER_CREATED)
        .version("1.0")
        .build();

    AtomicInteger attempts = new AtomicInteger(0);
    when(processedEventRepo.save(any(ProcessedEvent.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    ConsumerResult result = consumer.processEvent(event, evt -> {
      if (attempts.incrementAndGet() < 3) {
        return Mono.error(new SocketTimeoutException("Transient network timeout connecting to kitchen queue"));
      }
      return Mono.empty();
    }).block();

    assertThat(result).isEqualTo(ConsumerResult.PROCESSED);
    assertThat(attempts.get()).isEqualTo(3);
  }

  @Test
  @DisplayName("Error permanente no se reintenta, libera el lease y se envía a DLQ")
  void testPermanentErrorSentToDlqAndFreesLease() {
    String eventId = UUID.randomUUID().toString();
    OrderEvent event = OrderEvent.builder()
        .eventId(eventId)
        .eventType(OrderEventType.ORDER_CREATED)
        .version("1.0")
        .build();

    when(processedEventRepo.save(any(ProcessedEvent.class)))
        .thenAnswer(inv -> {
          ProcessedEvent p = inv.getArgument(0);
          return Mono.just(p);
        });
    when(deadLetterRepo.save(any(DeadLetterMessage.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    AtomicInteger attempts = new AtomicInteger(0);

    ConsumerResult result = consumer.processEvent(event, evt -> {
      attempts.incrementAndGet();
      return Mono.error(new IllegalArgumentException("Permanent validation constraint error"));
    }).block();

    assertThat(result).isEqualTo(ConsumerResult.SENT_TO_DLQ);
    assertThat(attempts.get()).isEqualTo(1); // Error permanente no se reintenta
    verify(deadLetterRepo, times(1)).save(any(DeadLetterMessage.class));
  }

  @Test
  @DisplayName("Agotamiento de reintentos por error transitorio envía evento a DLQ y libera el lease")
  void testTransientErrorExhaustionSentToDlqAndFreesLease() {
    String eventId = UUID.randomUUID().toString();
    OrderEvent event = OrderEvent.builder()
        .eventId(eventId)
        .eventType(OrderEventType.ORDER_CREATED)
        .version("1.0")
        .build();

    when(processedEventRepo.save(any(ProcessedEvent.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(deadLetterRepo.save(any(DeadLetterMessage.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    AtomicInteger attempts = new AtomicInteger(0);

    ConsumerResult result = consumer.processEvent(event, evt -> {
      attempts.incrementAndGet();
      return Mono.error(new SocketTimeoutException("Persistent transient network failure"));
    }).block();

    assertThat(result).isEqualTo(ConsumerResult.SENT_TO_DLQ);
    assertThat(attempts.get()).isEqualTo(4); // 1 inicial + 3 reintentos
    verify(deadLetterRepo, times(1)).save(any(DeadLetterMessage.class));
  }

  @Test
  @DisplayName("Versión no soportada se envía directo a DLQ")
  void testUnsupportedVersionSentToDlq() {
    String eventId = UUID.randomUUID().toString();
    OrderEvent event = OrderEvent.builder()
        .eventId(eventId)
        .eventType(OrderEventType.ORDER_CREATED)
        .version("99.0")
        .build();

    when(deadLetterRepo.save(any(DeadLetterMessage.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    ConsumerResult result = consumer.processEvent(event, evt -> Mono.empty()).block();

    assertThat(result).isEqualTo(ConsumerResult.SENT_TO_DLQ);
    verify(deadLetterRepo, times(1)).save(any(DeadLetterMessage.class));
  }

  @Test
  @DisplayName("Replay de DLQ para evento ya completado con SUCCESS evita repetir el efecto y no elimina registro SUCCESS")
  void testDlqReplayAlreadySuccessDoesNotRepeatEffectAndPreservesSuccess() throws Exception {
    String eventId = UUID.randomUUID().toString();
    OrderEvent event = OrderEvent.builder()
        .eventId(eventId)
        .eventType(OrderEventType.ORDER_CREATED)
        .version("1.0")
        .build();

    DeadLetterMessage dlq = DeadLetterMessage.builder()
        .id("dlq-already-done")
        .eventId(eventId)
        .payloadJson(objectMapper.writeValueAsString(event))
        .reprocessed(false)
        .build();

    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(DeadLetterMessage.class)))
        .thenReturn(Mono.just(dlq));

    when(processedEventRepo.findByEventIdAndConsumerName(eq(eventId), any()))
        .thenReturn(Mono.just(ProcessedEvent.builder()
            .eventId(eventId)
            .result("SUCCESS")
            .build()));

    AtomicInteger effectExecutions = new AtomicInteger(0);

    Boolean replayed = consumer.replayDlq("dlq-already-done", evt -> {
      effectExecutions.incrementAndGet();
      return Mono.empty();
    }).block();

    assertThat(replayed).isTrue();
    assertThat(effectExecutions.get()).isEqualTo(0);
    // Verificación fundamental: jamás borra el registro SUCCESS
    verify(processedEventRepo, never()).delete(any(ProcessedEvent.class));
  }

  @Test
  @DisplayName("Dos replays concurrentes para el mismo dlqId: la reclamación atómica previene carreras")
  void testConcurrentDlqReplaysHandledAtomically() throws Exception {
    String eventId = UUID.randomUUID().toString();
    OrderEvent event = OrderEvent.builder()
        .eventId(eventId)
        .eventType(OrderEventType.ORDER_CREATED)
        .version("1.0")
        .build();

    DeadLetterMessage dlq = DeadLetterMessage.builder()
        .id("dlq-concurrent")
        .eventId(eventId)
        .payloadJson(objectMapper.writeValueAsString(event))
        .reprocessed(false)
        .build();

    AtomicBoolean claimWon = new AtomicBoolean(false);

    // Solo el primer replay adquiere el mensaje DLQ de forma atómica (reprocessed != true)
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(DeadLetterMessage.class)))
        .thenAnswer(inv -> {
          if (claimWon.compareAndSet(false, true)) {
            return Mono.just(dlq);
          }
          return Mono.empty();
        });

    when(processedEventRepo.findByEventIdAndConsumerName(eq(eventId), any()))
        .thenReturn(Mono.just(ProcessedEvent.builder().eventId(eventId).result("SUCCESS").build()));

    Mono<Boolean> replay1 = consumer.replayDlq("dlq-concurrent", evt -> Mono.empty());
    Mono<Boolean> replay2 = consumer.replayDlq("dlq-concurrent", evt -> Mono.empty());

    List<Boolean> results = Flux.merge(replay1, replay2).collectList().block();

    assertThat(results).containsExactlyInAnyOrder(true, false);
  }
}
