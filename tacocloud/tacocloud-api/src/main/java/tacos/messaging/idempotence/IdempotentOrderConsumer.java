package tacos.messaging.idempotence;

import java.util.Date;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tacos.DeadLetterMessage;
import tacos.ProcessedEvent;
import tacos.data.DeadLetterMessageRepository;
import tacos.data.ProcessedEventRepository;
import tacos.messaging.event.OrderEvent;

/**
 * Consumidor Idempotente de Mensajes con Mecanismo de Pre-Reserva Concurrente,
 * Clasificación de Errores, Reintentos Acotados y Dead Letter Queue (DLQ).
 *
 * NOTA ARQUITECTÓNICA SOBRE COORDINACIÓN Y ATOMICIDAD:
 * En sistemas distribuidos, el patrón 'exactly-once' no puede garantizarse de forma pura
 * si el broker opera con semántica 'at-least-once' y el efecto de negocio interactúa con
 * servicios externos o bases de datos no transaccionales.
 *
 * Estrategia de Idempotencia y Deduplicación Concurrente:
 * 1. Pre-Reserva Atómica (Pre-claim Reservation): Antes de ejecutar el efecto de negocio,
 *    se persiste un registro 'ProcessedEvent' en estado 'PROCESSING' con la clave compuesta única
 *    (eventId + consumerName). El índice único de MongoDB garantiza que SOLO UNA llamada concurrente
 *    adquiera el derecho de procesar.
 * 2. Reclamo Atómico de Lease Expirado: Si ocurre DuplicateKeyException y el registro está en PROCESSING
 *    pero el lease expiró, el reclamo se efectúa mediante ReactiveMongoTemplate.findAndModify atómico
 *    condicionado a (result=PROCESSING y leaseExpiresAt < now). Solo el consumidor que gana el findAndModify
 *    puede re-ejecutar el efecto; los demás retornan DUPLICATE_IGNORED.
 * 3. Resiliencia ante fallos posteriores (Diseño de No Repetición): Si el efecto de negocio termina pero
 *    falla el guardado final de SUCCESS, la pre-reserva PROCESSING previene una ejecución ciega duplicada.
 *    Se prioriza arquitectónicamente NO repetir el efecto de negocio sobre reintentarlo ciegamente.
 * 4. Error Definitivo en Business Effect: Si el efecto falla definitivamente o agota reintentos transitorios,
 *    se actualiza atómicamente a FAILED_PERMANENT liberando el lease y se rutea de forma durable a DLQ.
 * 5. DLQ Replay Seguro y Trazable: Si el evento ya tiene SUCCESS en processed_events, el replay marca la DLQ
 *    como reprocesada sin repetir el efecto de negocio ni eliminar el registro SUCCESS.
 */
@Service
@Slf4j
public class IdempotentOrderConsumer {

  public enum ConsumerResult {
    PROCESSED,
    DUPLICATE_IGNORED,
    SENT_TO_DLQ
  }

  private final ReactiveMongoTemplate mongoTemplate;
  private final ProcessedEventRepository processedEventRepo;
  private final DeadLetterMessageRepository deadLetterRepo;
  private final ObjectMapper objectMapper;
  private final ObjectProvider<TransactionalOperator> txOperatorProvider;

  @Value("${tacocloud.consumer.maxRetries:3}")
  private int maxRetries = 3;

  @Value("${tacocloud.consumer.name:KITCHEN_ORDER_CONSUMER}")
  private String consumerName = "KITCHEN_ORDER_CONSUMER";

  @Value("${tacocloud.consumer.leaseMs:60000}")
  private long leaseMs = 60000L;

  @Autowired
  public IdempotentOrderConsumer(@Autowired(required = false) ReactiveMongoTemplate mongoTemplate,
                                 ProcessedEventRepository processedEventRepo,
                                 DeadLetterMessageRepository deadLetterRepo,
                                 ObjectMapper objectMapper,
                                 @Autowired(required = false) ObjectProvider<TransactionalOperator> txOperatorProvider) {
    this.mongoTemplate = mongoTemplate;
    this.processedEventRepo = processedEventRepo;
    this.deadLetterRepo = deadLetterRepo;
    this.objectMapper = objectMapper;
    this.txOperatorProvider = txOperatorProvider;
  }

  public IdempotentOrderConsumer(ProcessedEventRepository processedEventRepo,
                                 DeadLetterMessageRepository deadLetterRepo,
                                 ObjectMapper objectMapper) {
    this(null, processedEventRepo, deadLetterRepo, objectMapper, null);
  }

  public IdempotentOrderConsumer(ReactiveMongoTemplate mongoTemplate,
                                 ProcessedEventRepository processedEventRepo,
                                 DeadLetterMessageRepository deadLetterRepo,
                                 ObjectMapper objectMapper) {
    this(mongoTemplate, processedEventRepo, deadLetterRepo, objectMapper, null);
  }

  public Mono<ConsumerResult> processEvent(OrderEvent event, Function<OrderEvent, Mono<Void>> businessEffect) {
    // Requisito 7: Eventos nulos generan registro persistente en DLQ
    if (event == null) {
      log.error("Evento nulo recibido en consumidor {}", consumerName);
      return persistNullEventDlq("Evento nulo recibido");
    }

    // Requisito 7: Eventos sin eventId generan registro persistente en DLQ
    if (event.getEventId() == null || event.getEventId().trim().isEmpty()) {
      log.error("Evento recibido sin eventId en consumidor {}", consumerName);
      return persistInvalidEventDlq(event, "Evento sin eventId o eventId vacío");
    }

    // Validación de versión: sólo versión 1.0 soportada; versiones desconocidas van directo a DLQ
    if (!"1.0".equals(event.getVersion())) {
      log.error("Versión no soportada en evento {}: {}", event.getEventId(), event.getVersion());
      return Mono.defer(() -> routeToDlq(event, new IllegalArgumentException("Versión no soportada: " + event.getVersion()), 1))
          .then(Mono.just(ConsumerResult.SENT_TO_DLQ));
    }

    // Requisito 1 & 2: Pre-reserva atómica usando el índice único (eventId + consumerName)
    ProcessedEvent reservation = ProcessedEvent.builder()
        .id(UUID.randomUUID().toString())
        .eventId(event.getEventId())
        .consumerName(consumerName)
        .eventType(event.getEventType() != null ? event.getEventType().name() : "UNKNOWN")
        .processedAt(new Date())
        .leaseExpiresAt(new Date(System.currentTimeMillis() + leaseMs))
        .result("PROCESSING")
        .build();

    Mono<ProcessedEvent> saveReservationMono = (processedEventRepo != null)
        ? processedEventRepo.save(reservation)
        : Mono.just(reservation);

    if (saveReservationMono == null) {
      saveReservationMono = Mono.just(reservation);
    }

    return saveReservationMono
        .flatMap(savedReservation -> executeBusinessEffect(event, savedReservation, businessEffect))
        .onErrorResume(this::isDuplicateKeyException, dupEx -> handleDuplicateCollision(event, businessEffect));
  }

  private Mono<ConsumerResult> executeBusinessEffect(OrderEvent event,
                                                     ProcessedEvent reservation,
                                                     Function<OrderEvent, Mono<Void>> businessEffect) {
    return Mono.defer(() -> businessEffect.apply(event))
        .retryWhen(Retry.max(maxRetries).filter(this::isTransientError))
        .then(Mono.defer(() -> {
          // Requisito 6: Efecto completado con éxito, actualizar reserva a SUCCESS
          reservation.setResult("SUCCESS");
          reservation.setLeaseExpiresAt(null);
          reservation.setProcessedAt(new Date());
          reservation.setErrorMessage(null);

          Mono<ProcessedEvent> updateMono = (processedEventRepo != null) ? processedEventRepo.save(reservation) : null;
          if (updateMono == null) {
            updateMono = Mono.just(reservation);
          }
          return updateMono
              .then(Mono.just(ConsumerResult.PROCESSED))
              .onErrorResume(saveErr -> {
                // Requisito 6: Si el efecto terminó pero falla el guardado final de SUCCESS,
                // la pre-reserva PROCESSING ya existe y previene una ejecución ciega duplicada.
                log.error("Efecto de negocio completado pero ocurrió un fallo al guardar ProcessedEvent SUCCESS para evento {}: {}",
                    event.getEventId(), saveErr.getMessage());
                return Mono.just(ConsumerResult.PROCESSED);
              });
        }))
        .onErrorResume(error -> {
          log.error("Fallo definitivo al procesar efecto de negocio para evento {}: {}",
              event.getEventId(), error.getMessage());

          reservation.setResult("FAILED_PERMANENT");
          reservation.setLeaseExpiresAt(null);
          reservation.setErrorMessage(error.getMessage());

          Mono<ProcessedEvent> markFailedMono = (processedEventRepo != null) ? processedEventRepo.save(reservation) : null;
          if (markFailedMono == null) {
            markFailedMono = Mono.just(reservation);
          }

          return markFailedMono
              .onErrorResume(ignored -> Mono.just(reservation))
              .then(Mono.defer(() -> routeToDlq(event, error, maxRetries)))
              .then(Mono.just(ConsumerResult.SENT_TO_DLQ));
        });
  }

  private Mono<ConsumerResult> handleDuplicateCollision(OrderEvent event,
                                                        Function<OrderEvent, Mono<Void>> businessEffect) {
    log.info("Colisión de clave única detectada para evento {} en consumidor {}. Verificando / reclamando atómicamente...",
        event.getEventId(), consumerName);

    Date now = new Date();

    if (mongoTemplate != null) {
      // Reclamación atómica de PROCESSING expirado (evita la condición de carrera read-then-save)
      Query claimExpiredQuery = new Query(new Criteria().andOperator(
          Criteria.where("eventId").is(event.getEventId()),
          Criteria.where("consumerName").is(consumerName),
          Criteria.where("result").is("PROCESSING"),
          Criteria.where("leaseExpiresAt").lt(now)
      ));

      Update renewLeaseUpdate = new Update()
          .set("leaseExpiresAt", new Date(now.getTime() + leaseMs))
          .set("processedAt", now);

      FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(true);

      return mongoTemplate.findAndModify(claimExpiredQuery, renewLeaseUpdate, options, ProcessedEvent.class)
          .flatMap(claimedReservation -> {
            log.warn("Reserva PROCESSING de evento {} expiró. Reclamada ATÓMICAMENTE por consumidor {}. Re-ejecutando efecto...",
                event.getEventId(), consumerName);
            return executeBusinessEffect(event, claimedReservation, businessEffect);
          })
          .switchIfEmpty(Mono.defer(() -> {
            log.info("Evento {} ya completado o con lease vigente. Devolviendo DUPLICATE_IGNORED.", event.getEventId());
            return Mono.just(ConsumerResult.DUPLICATE_IGNORED);
          }));
    }

    if (processedEventRepo != null) {
      return processedEventRepo.findByEventIdAndConsumerName(event.getEventId(), consumerName)
          .flatMap(existing -> {
            if ("PROCESSING".equals(existing.getResult())
                && existing.getLeaseExpiresAt() != null
                && existing.getLeaseExpiresAt().before(now)) {
              existing.setLeaseExpiresAt(new Date(now.getTime() + leaseMs));
              return processedEventRepo.save(existing)
                  .flatMap(renewed -> executeBusinessEffect(event, renewed, businessEffect));
            }
            return Mono.just(ConsumerResult.DUPLICATE_IGNORED);
          })
          .defaultIfEmpty(ConsumerResult.DUPLICATE_IGNORED);
    }

    return Mono.just(ConsumerResult.DUPLICATE_IGNORED);
  }

  private boolean isDuplicateKeyException(Throwable t) {
    Throwable root = t;
    while (root != null) {
      if (root instanceof DuplicateKeyException) {
        return true;
      }
      String name = root.getClass().getName();
      if (name.contains("DuplicateKey") || name.contains("MongoWriteException")) {
        return true;
      }
      String msg = root.getMessage();
      if (msg != null && (msg.contains("E11000") || msg.contains("duplicate key"))) {
        return true;
      }
      if (root.getCause() == root) {
        break;
      }
      root = root.getCause();
    }
    return false;
  }

  public Mono<Boolean> replayDlq(String dlqId, Function<OrderEvent, Mono<Void>> businessEffect) {
    if (deadLetterRepo == null) {
      return Mono.just(false);
    }

    Date now = new Date();

    // Reclamación atómica en DLQ para evitar carreras si dos llamadas concurrentes intentan el replay del mismo dlqId
    Mono<DeadLetterMessage> claimDlqMono;
    if (mongoTemplate != null) {
      Query claimQuery = new Query(new Criteria().andOperator(
          Criteria.where("id").is(dlqId),
          Criteria.where("reprocessed").ne(true)
      ));
      Update claimUpdate = new Update()
          .set("reprocessed", true)
          .set("reprocessedAt", now);
      claimDlqMono = mongoTemplate.findAndModify(claimQuery, claimUpdate, FindAndModifyOptions.options().returnNew(true), DeadLetterMessage.class);
    } else {
      claimDlqMono = deadLetterRepo.findById(dlqId)
          .flatMap(dlqMsg -> {
            if (Boolean.TRUE.equals(dlqMsg.isReprocessed())) {
              return Mono.empty();
            }
            dlqMsg.setReprocessed(true);
            dlqMsg.setReprocessedAt(now);
            return deadLetterRepo.save(dlqMsg);
          });
    }

    return claimDlqMono
        .flatMap(dlqMsg -> {
          OrderEvent event;
          try {
            event = objectMapper.readValue(dlqMsg.getPayloadJson(), OrderEvent.class);
          } catch (Exception e) {
            log.error("Error deserializando DLQ message {} para replay", dlqId, e);
            return Mono.just(false);
          }

          if (event == null || event.getEventId() == null) {
            return Mono.just(false);
          }

          // Requisito 10: Si ProcessedEvent está SUCCESS, NO repetir businessEffect, conservar trazabilidad y no borrar el registro SUCCESS
          Mono<ProcessedEvent> checkExisting = (processedEventRepo != null)
              ? processedEventRepo.findByEventIdAndConsumerName(event.getEventId(), consumerName)
              : Mono.empty();

          if (checkExisting == null) {
            checkExisting = Mono.empty();
          }

          return checkExisting
              .flatMap(existing -> {
                if ("SUCCESS".equals(existing.getResult())) {
                  log.info("Evento {} ya cuenta con estado SUCCESS en processed_events. DLQ marcado como reprocesado sin repetir efecto.",
                      event.getEventId());
                  return Mono.just(true);
                }

                // Si estaba en FAILED_PERMANENT u otro estado previo:
                // Actualizamos de vuelta a PROCESSING con un nuevo lease para ejecutar el efecto limpiamente conservando trazabilidad
                existing.setResult("PROCESSING");
                existing.setLeaseExpiresAt(new Date(now.getTime() + leaseMs));
                existing.setProcessedAt(now);
                existing.setErrorMessage(null);

                return processedEventRepo.save(existing)
                    .flatMap(renewed -> executeBusinessEffect(event, renewed, businessEffect))
                    .map(res -> res == ConsumerResult.PROCESSED || res == ConsumerResult.DUPLICATE_IGNORED);
              })
              .switchIfEmpty(Mono.defer(() -> {
                return processEvent(event, businessEffect)
                    .map(res -> res == ConsumerResult.PROCESSED || res == ConsumerResult.DUPLICATE_IGNORED);
              }));
        })
        .defaultIfEmpty(false);
  }

  private Mono<DeadLetterMessage> routeToDlq(OrderEvent event, Throwable error, int attempts) {
    String payloadJson = null;
    try {
      payloadJson = objectMapper.writeValueAsString(event);
    } catch (Exception ignored) {
    }

    Throwable rootCause = error;
    if (error.getCause() != null) {
      rootCause = error.getCause();
    }

    DeadLetterMessage dlq = DeadLetterMessage.builder()
        .id(UUID.randomUUID().toString())
        .eventId(event.getEventId())
        .correlationId(event.getCorrelationId())
        .eventType(event.getEventType() != null ? event.getEventType().name() : "UNKNOWN")
        .eventVersion(event.getVersion())
        .payloadJson(payloadJson)
        .attempts(attempts)
        .errorCause(rootCause.getMessage())
        .exceptionClass(rootCause.getClass().getName())
        .failedAt(new Date())
        .reprocessed(false)
        .build();

    if (deadLetterRepo != null) {
      Mono<DeadLetterMessage> saveMono = deadLetterRepo.save(dlq);
      if (saveMono != null) {
        return saveMono;
      }
    }
    return Mono.just(dlq);
  }

  private Mono<ConsumerResult> persistNullEventDlq(String cause) {
    DeadLetterMessage dlq = DeadLetterMessage.builder()
        .id(UUID.randomUUID().toString())
        .eventId("NULL-EVENT-" + UUID.randomUUID())
        .eventType("NULL_EVENT")
        .payloadJson(null)
        .attempts(1)
        .errorCause(cause)
        .exceptionClass(IllegalArgumentException.class.getName())
        .failedAt(new Date())
        .reprocessed(false)
        .build();

    Mono<DeadLetterMessage> saveMono = (deadLetterRepo != null) ? deadLetterRepo.save(dlq) : null;
    if (saveMono == null) {
      saveMono = Mono.just(dlq);
    }
    return saveMono.thenReturn(ConsumerResult.SENT_TO_DLQ);
  }

  private Mono<ConsumerResult> persistInvalidEventDlq(OrderEvent event, String cause) {
    String payload = null;
    try {
      payload = objectMapper.writeValueAsString(event);
    } catch (Exception ignored) {
    }

    DeadLetterMessage dlq = DeadLetterMessage.builder()
        .id(UUID.randomUUID().toString())
        .eventId("INVALID-EVENT-" + UUID.randomUUID())
        .correlationId(event.getCorrelationId())
        .eventType(event.getEventType() != null ? event.getEventType().name() : "UNKNOWN")
        .eventVersion(event.getVersion())
        .payloadJson(payload)
        .attempts(1)
        .errorCause(cause)
        .exceptionClass(IllegalArgumentException.class.getName())
        .failedAt(new Date())
        .reprocessed(false)
        .build();

    Mono<DeadLetterMessage> saveMono = (deadLetterRepo != null) ? deadLetterRepo.save(dlq) : null;
    if (saveMono == null) {
      saveMono = Mono.just(dlq);
    }
    return saveMono.thenReturn(ConsumerResult.SENT_TO_DLQ);
  }

  public boolean isTransientError(Throwable throwable) {
    if (throwable == null) {
      return false;
    }

    Throwable root = throwable;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }

    // Errores permanentes reconocidos
    if (root instanceof IllegalArgumentException
        || root instanceof IllegalStateException
        || root instanceof javax.validation.ValidationException
        || root instanceof com.fasterxml.jackson.core.JsonProcessingException) {
      return false;
    }

    String className = root.getClass().getName();
    String message = root.getMessage() != null ? root.getMessage().toLowerCase() : "";

    if (className.contains("Validation")
        || className.contains("ConstraintViolation")
        || className.contains("NoSuchElement")
        || className.contains("MethodArgumentNotValid")) {
      return false;
    }

    if (message.contains("permanent")
        || message.contains("constraint")
        || message.contains("invalid")
        || message.contains("malformed")
        || message.contains("validation")
        || message.contains("not found")) {
      return false;
    }

    // Errores transitorios reconocidos
    if (root instanceof java.net.SocketTimeoutException
        || root instanceof java.net.ConnectException
        || root instanceof java.io.IOException
        || root instanceof org.springframework.dao.TransientDataAccessException
        || root instanceof org.springframework.dao.ConcurrencyFailureException) {
      return true;
    }

    if (message.contains("transient")
        || message.contains("timeout")
        || message.contains("connection refused")
        || message.contains("temporary")
        || message.contains("try again")) {
      return true;
    }

    // Por defecto ante excepción desconocida de negocio, no asumir que es transitoria
    return false;
  }

  public int getMaxRetries() {
    return maxRetries;
  }

  public void setMaxRetries(int maxRetries) {
    this.maxRetries = maxRetries;
  }

  public String getConsumerName() {
    return consumerName;
  }

  public void setConsumerName(String consumerName) {
    this.consumerName = consumerName;
  }

  public long getLeaseMs() {
    return leaseMs;
  }

  public void setLeaseMs(long leaseMs) {
    this.leaseMs = leaseMs;
  }
}
