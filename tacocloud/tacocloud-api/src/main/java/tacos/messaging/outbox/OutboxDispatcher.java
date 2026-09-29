package tacos.messaging.outbox;

import java.util.Date;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.OutboxEvent;
import tacos.OutboxStatus;
import tacos.data.OutboxEventRepository;
import tacos.messaging.OrderMessagingService;
import tacos.messaging.event.OrderEvent;

/**
 * Despachador de Outbox Reactivo.
 *
 * Características de resiliencia y concurrencia distribuida:
 * 1. Reclamo atómico con findAndModify para evitar que dos dispatchers concurrentes procesen el mismo evento.
 * 2. Recuperación de eventos abandonados en PUBLISHING mediante lease y timeout.
 * 3. Preservación estricta de orden FIFO por aggregateId entre múltiples dispatchers: no se reclama un evento si
 *    el agregado tiene otro evento en PUBLISHING activo o si existe un evento anterior no publicado.
 * 4. Control de Ownership del Lease: las actualizaciones finales de éxito o error son condicionales (lockedBy == dispatcherId).
 *    Si el lease expiró y otro dispatcher reclamó el evento, el dispatcher original no sobrescribe el estado del nuevo dueño.
 * 5. Si no hay transporte de mensajería configurado o disponible, el evento permanece en estado recuperable (NEW), nunca PUBLISHED.
 * 6. Envío reactivo no bloqueante mediante sendOrderReactive; si falla, registra el error, incrementa intentos y libera el lease.
 */
@Service
@Slf4j
public class OutboxDispatcher {

  private final ReactiveMongoTemplate mongoTemplate;
  private final OutboxEventRepository outboxRepo;
  private final OrderMessagingService messagingService;
  private final ObjectMapper objectMapper;

  private String dispatcherId = UUID.randomUUID().toString();

  @Value("${tacocloud.outbox.maxAttempts:3}")
  private int maxAttempts = 3;

  @Value("${tacocloud.outbox.leaseDurationMs:30000}")
  private long leaseDurationMs = 30000L;

  @Autowired
  public OutboxDispatcher(ReactiveMongoTemplate mongoTemplate,
                          OutboxEventRepository outboxRepo,
                          @Autowired(required = false) OrderMessagingService messagingService,
                          ObjectMapper objectMapper) {
    this.mongoTemplate = mongoTemplate;
    this.outboxRepo = outboxRepo;
    this.messagingService = messagingService;
    this.objectMapper = objectMapper;
  }

  public Mono<OutboxEvent> claimNextPendingEvent() {
    Date now = new Date();

    // 1. Obtener aggregateIds que actualmente tienen un evento en PUBLISHING con lease vigente.
    // Esto previene que otro dispatcher procese concurrentemente un segundo evento del mismo agregado fuera de orden.
    Query activePublishingQuery = new Query(new Criteria().andOperator(
        Criteria.where("status").is(OutboxStatus.PUBLISHING),
        Criteria.where("leaseExpiresAt").gt(now)
    ));
    activePublishingQuery.fields().include("aggregateId");

    return mongoTemplate.find(activePublishingQuery, OutboxEvent.class)
        .map(OutboxEvent::getAggregateId)
        .filter(aggId -> aggId != null)
        .collectList()
        .flatMap(inFlightAggregates -> {
          // Criterio 1: Eventos NEW cuyo aggregateId no esté actualmente en vuelo
          Criteria isNew = Criteria.where("status").is(OutboxStatus.NEW);
          if (!inFlightAggregates.isEmpty()) {
            isNew = isNew.and("aggregateId").nin(inFlightAggregates);
          }

          // Criterio 2: Eventos en PUBLISHING abandonados (lease expirado o timeout de último intento)
          Criteria isAbandoned = new Criteria().andOperator(
              Criteria.where("status").is(OutboxStatus.PUBLISHING),
              new Criteria().orOperator(
                  Criteria.where("leaseExpiresAt").lt(now),
                  new Criteria().andOperator(
                      Criteria.where("leaseExpiresAt").is(null),
                      Criteria.where("lastAttemptAt").lt(new Date(now.getTime() - leaseDurationMs))
                  )
              )
          );

          Query query = new Query(new Criteria().orOperator(isNew, isAbandoned));
          query.with(Sort.by(Sort.Direction.ASC, "createdAt"));

          Update update = new Update();
          update.set("status", OutboxStatus.PUBLISHING);
          update.set("lockedBy", dispatcherId);
          update.set("lockedAt", now);
          update.set("leaseExpiresAt", new Date(now.getTime() + leaseDurationMs));
          update.set("lastAttemptAt", now);

          FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(true);

          return mongoTemplate.findAndModify(query, update, options, OutboxEvent.class)
              .flatMap(claimedEvent -> {
                // Verificación de orden FIFO por agregado: si existe un evento anterior
                // para este aggregateId que aún no ha sido publicado, liberamos este evento de vuelta a NEW.
                if (claimedEvent.getAggregateId() == null || claimedEvent.getCreatedAt() == null) {
                  return Mono.just(claimedEvent);
                }

                Query earlierQuery = new Query(new Criteria().andOperator(
                    Criteria.where("aggregateId").is(claimedEvent.getAggregateId()),
                    Criteria.where("id").ne(claimedEvent.getId()),
                    Criteria.where("status").ne(OutboxStatus.PUBLISHED),
                    Criteria.where("createdAt").lt(claimedEvent.getCreatedAt())
                ));

                return mongoTemplate.exists(earlierQuery, OutboxEvent.class)
                    .flatMap(hasEarlier -> {
                      if (hasEarlier) {
                        log.info("Evento más antiguo no publicado para aggregateId {}. Liberando evento {} para preservar FIFO.",
                            claimedEvent.getAggregateId(), claimedEvent.getId());
                        Query releaseQuery = new Query(new Criteria().andOperator(
                            Criteria.where("id").is(claimedEvent.getId()),
                            Criteria.where("status").is(OutboxStatus.PUBLISHING),
                            Criteria.where("lockedBy").is(dispatcherId)
                        ));
                        Update releaseUpdate = new Update()
                            .set("status", OutboxStatus.NEW)
                            .unset("lockedBy")
                            .unset("lockedAt")
                            .unset("leaseExpiresAt");
                        return mongoTemplate.findAndModify(releaseQuery, releaseUpdate, OutboxEvent.class)
                            .then(Mono.empty());
                      }
                      return Mono.just(claimedEvent);
                    });
              });
        });
  }

  public Mono<OutboxEvent> dispatch(OutboxEvent outboxEvent) {
    if (outboxEvent == null) {
      return Mono.empty();
    }

    // Si no hay transporte configurado o disponible, el evento NUNCA debe marcarse como publicado.
    // Se regresa a estado recuperable (NEW) y se liberan las marcas de bloqueo/lease de forma atómica y condicional.
    if (messagingService == null) {
      log.warn("Transporte de mensajería no disponible para evento outbox {}. Permanece en estado recuperable (NEW).",
          outboxEvent.getId());
      return updateEventStateConditional(
          outboxEvent.getId(),
          OutboxStatus.NEW,
          outboxEvent.getAttempts(),
          "Transporte de mensajería no disponible"
      );
    }

    return Mono.fromCallable(() -> {
      if (outboxEvent.getPayloadJson() != null) {
        return objectMapper.readValue(outboxEvent.getPayloadJson(), OrderEvent.class);
      }
      throw new IllegalArgumentException("Payload de evento outbox es nulo o inválido");
    })
    .flatMap(event -> {
      Mono<Void> sendMono = messagingService.sendOrderReactive(event);
      if (sendMono == null) {
        sendMono = Mono.fromRunnable(() -> messagingService.sendOrder(event));
      }
      return sendMono;
    })
    .then(Mono.defer(() -> markPublishedConditional(outboxEvent.getId())))
    .onErrorResume(error -> {
      log.warn("Fallo publicando evento outbox {}: {}", outboxEvent.getId(), error.getMessage());
      int currentAttempts = outboxEvent.getAttempts() + 1;
      OutboxStatus nextStatus = (currentAttempts >= maxAttempts) ? OutboxStatus.FAILED : OutboxStatus.NEW;
      return updateEventStateConditional(
          outboxEvent.getId(),
          nextStatus,
          currentAttempts,
          error.getMessage()
      );
    });
  }

  private Mono<OutboxEvent> markPublishedConditional(String eventId) {
    Query query = new Query(new Criteria().andOperator(
        Criteria.where("id").is(eventId),
        Criteria.where("status").is(OutboxStatus.PUBLISHING),
        Criteria.where("lockedBy").is(dispatcherId)
    ));

    Update update = new Update()
        .set("status", OutboxStatus.PUBLISHED)
        .set("lastAttemptAt", new Date())
        .unset("lockedBy")
        .unset("lockedAt")
        .unset("leaseExpiresAt")
        .unset("errorMessage");

    FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(true);

    return mongoTemplate.findAndModify(query, update, options, OutboxEvent.class)
        .switchIfEmpty(Mono.defer(() -> {
          log.warn("Dispatcher {} perdió el lease para el evento {}. Se omite actualización a PUBLISHED.",
              dispatcherId, eventId);
          return Mono.empty();
        }));
  }

  private Mono<OutboxEvent> updateEventStateConditional(String eventId,
                                                        OutboxStatus newStatus,
                                                        int attempts,
                                                        String errorMessage) {
    Query query = new Query(new Criteria().andOperator(
        Criteria.where("id").is(eventId),
        Criteria.where("status").is(OutboxStatus.PUBLISHING),
        Criteria.where("lockedBy").is(dispatcherId)
    ));

    Update update = new Update()
        .set("status", newStatus)
        .set("attempts", attempts)
        .set("lastAttemptAt", new Date())
        .set("errorMessage", errorMessage)
        .unset("lockedBy")
        .unset("lockedAt")
        .unset("leaseExpiresAt");

    FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(true);

    return mongoTemplate.findAndModify(query, update, options, OutboxEvent.class)
        .switchIfEmpty(Mono.defer(() -> {
          log.warn("Dispatcher {} perdió el lease para el evento {}. No se sobrescribe el estado del nuevo owner.",
              dispatcherId, eventId);
          return Mono.empty();
        }));
  }

  public Flux<OutboxEvent> dispatchPendingBatch(int batchSize) {
    return Flux.range(0, batchSize)
        .concatMap(i -> claimNextPendingEvent())
        .takeWhile(evt -> evt != null)
        .concatMap(this::dispatch);
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public long getLeaseDurationMs() {
    return leaseDurationMs;
  }

  public void setLeaseDurationMs(long leaseDurationMs) {
    this.leaseDurationMs = leaseDurationMs;
  }

  public String getDispatcherId() {
    return dispatcherId;
  }

  public void setDispatcherId(String dispatcherId) {
    this.dispatcherId = dispatcherId;
  }
}
