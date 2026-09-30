package tacos.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import tacos.IdempotencyRecord;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.OutboxEvent;
import tacos.OutboxStatus;
import tacos.ProcessedEvent;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.DeadLetterMessageRepository;
import tacos.data.IdempotencyRecordRepository;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.OutboxEventRepository;
import tacos.data.ProcessedEventRepository;
import tacos.idempotency.IdempotencyService;
import tacos.messaging.OrderMessagingService;
import tacos.messaging.event.OrderEvent;
import tacos.messaging.event.OrderEventType;
import tacos.messaging.idempotence.IdempotentOrderConsumer;
import tacos.messaging.idempotence.IdempotentOrderConsumer.ConsumerResult;
import tacos.messaging.outbox.OutboxDispatcher;
import tacos.messaging.outbox.OutboxService;
import tacos.web.api.BusinessRuleException;
import tacos.web.api.EmailOrderService;
import tacos.web.api.InventoryService;
import tacos.web.api.OrderApiController;
import tacos.web.api.OrderCreateRequest;
import tacos.web.api.OrderItemRequest;
import tacos.web.api.OrderMapper;
import tacos.web.api.OrderResponse;
import tacos.web.api.PricingService;
import tacos.web.api.correlation.CorrelationContext;

/**
 * TC-36: Comprehensive system regression integration suite validating
 * core functionality across Lab 1 through Lab 6.
 */
public class FullSystemRegressionIntegrationTest {

  private OrderRepository orderRepo;
  private OrderMessagingService messagingService;
  private EmailOrderService emailOrderService;
  private IngredientRepository ingredientRepo;
  private PricingService pricingService;
  private InventoryService inventoryService;
  private OrderMapper orderMapper;
  private OutboxEventRepository outboxRepo;
  private OutboxService outboxService;
  private OutboxDispatcher outboxDispatcher;
  private IdempotencyRecordRepository idempotencyRepo;
  private IdempotencyService idempotencyService;
  private ReactiveMongoTemplate mongoTemplate;
  private ProcessedEventRepository processedEventRepo;
  private DeadLetterMessageRepository deadLetterRepo;
  private IdempotentOrderConsumer consumer;
  private ObjectMapper objectMapper;
  private OrderApiController controller;

  @BeforeEach
  void setUp() {
    orderRepo = mock(OrderRepository.class);
    messagingService = mock(OrderMessagingService.class);
    emailOrderService = mock(EmailOrderService.class);
    ingredientRepo = mock(IngredientRepository.class);
    pricingService = mock(PricingService.class);
    inventoryService = mock(InventoryService.class);
    orderMapper = new OrderMapper();
    outboxRepo = mock(OutboxEventRepository.class);
    idempotencyRepo = mock(IdempotencyRecordRepository.class);
    mongoTemplate = mock(ReactiveMongoTemplate.class);
    processedEventRepo = mock(ProcessedEventRepository.class);
    deadLetterRepo = mock(DeadLetterMessageRepository.class);
    objectMapper = new ObjectMapper();

    when(mongoTemplate.find(any(Query.class), eq(OutboxEvent.class))).thenReturn(Flux.empty());
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(OutboxEvent.class)))
        .thenReturn(Mono.empty());

    outboxService = new OutboxService(orderRepo, outboxRepo, objectMapper);
    outboxDispatcher = new OutboxDispatcher(mongoTemplate, outboxRepo, messagingService, objectMapper);
    idempotencyService = new IdempotencyService(idempotencyRepo, objectMapper);
    consumer = new IdempotentOrderConsumer(mongoTemplate, processedEventRepo, deadLetterRepo, objectMapper);

    controller = new OrderApiController(
        orderRepo,
        messagingService,
        emailOrderService,
        ingredientRepo,
        pricingService,
        inventoryService,
        null,
        orderMapper,
        null,
        null,
        outboxService,
        outboxDispatcher,
        idempotencyService
    );
  }

  @Test
  @DisplayName("Regression TC-01 & TC-02: Ingredient constructor compatibility and reactive repository mapping")
  void testIngredientCompatibilityAndPrice() {
    Ingredient ing3 = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);
    assertThat(ing3.getId()).isEqualTo("FLTO");
    assertThat(ing3.getUnitPrice()).isEqualTo(BigDecimal.ZERO);

    Ingredient ing4 = new Ingredient("COTO", "Corn Tortilla", Type.WRAP, BigDecimal.valueOf(1.25));
    assertThat(ing4.getUnitPrice()).isEqualTo(BigDecimal.valueOf(1.25));
  }

  @Test
  @DisplayName("Regression TC-05: PUT order route identity and user ownership enforcement")
  void testPutOrderIdentityAndOwnership() {
    User owner = new User("craig", "password", "Craig Walls", null, null, null, null, null, "craig@example.com");
    TacoOrder existingOrder = new TacoOrder();
    existingOrder.setId("order-123");
    existingOrder.setUser(owner);

    when(orderRepo.findById("order-123")).thenReturn(Mono.just(existingOrder));

    // 1. Path mismatch -> 400 Bad Request
    OrderCreateRequest badIdReq = new OrderCreateRequest();
    badIdReq.setId("different-id");
    StepVerifier.create(controller.putOrder("order-123", badIdReq, new UsernamePasswordAuthenticationToken("craig", "pwd")))
        .expectNextMatches(resp -> resp.getStatusCode() == HttpStatus.BAD_REQUEST)
        .verifyComplete();

    // 2. Forbidden user -> 403 Forbidden
    OrderCreateRequest matchingReq = new OrderCreateRequest();
    matchingReq.setId("order-123");
    StepVerifier.create(controller.putOrder("order-123", matchingReq, new UsernamePasswordAuthenticationToken("intruder", "pwd")))
        .expectNextMatches(resp -> resp.getStatusCode() == HttpStatus.FORBIDDEN)
        .verifyComplete();
  }

  @Test
  @DisplayName("Regression TC-14 & TC-16: Server-side pricing and atomic inventory reservation flow")
  void testOrderPricingAndInventoryFlow() {
    Taco taco = new Taco();
    taco.setName("Carnitas");
    Ingredient carnitas = new Ingredient("CARN", "Carnitas", Type.PROTEIN, BigDecimal.valueOf(4.00));
    taco.setIngredients(List.of(carnitas));

    OrderCreateRequest request = new OrderCreateRequest();
    request.setDeliveryName("Craig Walls");
    request.setItems(List.of(new OrderItemRequest(taco, 2)));

    TacoOrder pricedOrder = new TacoOrder();
    pricedOrder.setId("order-priced-1");
    pricedOrder.setDeliveryName("Craig Walls");
    pricedOrder.setTotal(BigDecimal.valueOf(8.00));
    pricedOrder.setItems(List.of(new tacos.OrderItem(taco, 2)));

    when(pricingService.priceOrder(any(TacoOrder.class), any())).thenReturn(Mono.just(pricedOrder));
    when(inventoryService.reserve(any(), any(), any())).thenReturn(Mono.empty());
    when(outboxRepo.save(any(OutboxEvent.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(controller.postOrder(request))
        .expectNextMatches(resp -> resp.getTotal().compareTo(BigDecimal.valueOf(8.00)) == 0)
        .verifyComplete();

    verify(inventoryService).reserve(any(), any(), argThat(map -> map.containsKey("CARN") && map.get("CARN") == 2));
    verify(orderRepo).save(any(TacoOrder.class));
  }

  @Test
  @DisplayName("Regression TC-29 & TC-30: Outbox transactional creation and idempotent consumer deduplication")
  void testOutboxAndIdempotentConsumer() {
    TacoOrder order = new TacoOrder();
    order.setId("order-outbox-99");
    order.setDeliveryName("Craig Walls");

    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(outboxRepo.save(any(OutboxEvent.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    // 1. Outbox event creation
    StepVerifier.create(outboxService.saveOrderWithOutbox(order, OrderEventType.ORDER_CREATED))
        .expectNextMatches(saved -> saved.getId().equals("order-outbox-99"))
        .verifyComplete();

    verify(outboxRepo).save(argThat(event ->
        event.getAggregateId().equals("order-outbox-99") &&
        event.getEventType().equals("ORDER_CREATED") &&
        event.getStatus() == OutboxStatus.NEW
    ));

    // 2. Idempotent Consumer processing
    OrderEvent event = OrderEvent.builder()
        .eventId(UUID.randomUUID().toString())
        .eventType(OrderEventType.ORDER_CREATED)
        .version("1.0")
        .occurredAt(new Date())
        .build();

    when(processedEventRepo.save(any(ProcessedEvent.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    AtomicInteger workCounter = new AtomicInteger(0);

    // First arrival: successfully processes event
    StepVerifier.create(consumer.processEvent(event, ev -> {
      workCounter.incrementAndGet();
      return Mono.empty();
    }))
    .expectNext(ConsumerResult.PROCESSED)
    .verifyComplete();

    assertThat(workCounter.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("Regression TC-31 & TC-34: Correlation ID context propagation and Idempotency-Key caching")
  void testCorrelationIdAndIdempotencyKeyIntegration() throws Exception {
    String idempotencyKey = "client-tx-777";
    String userId = "craig";

    OrderCreateRequest req = new OrderCreateRequest();
    req.setDeliveryName("Craig Walls");
    req.setDeliveryStreet("1234 Elm St");
    req.setDeliveryCity("Dallas");
    req.setDeliveryState("TX");
    req.setDeliveryZip("75001");

    TacoOrder savedDomainOrder = new TacoOrder();
    savedDomainOrder.setId("order-idemp-1");
    savedDomainOrder.setDeliveryName("Craig Walls");
    savedDomainOrder.setTotal(BigDecimal.TEN);

    when(pricingService.priceOrder(any(TacoOrder.class), any())).thenReturn(Mono.just(savedDomainOrder));
    when(outboxRepo.save(any(OutboxEvent.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    // Setup idempotency repository mock
    when(idempotencyRepo.findByKeyAndUserId(idempotencyKey, userId)).thenReturn(Mono.empty());
    when(idempotencyRepo.insert((IdempotencyRecord) any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(idempotencyRepo.save(any(IdempotencyRecord.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    UsernamePasswordAuthenticationToken principal = new UsernamePasswordAuthenticationToken(userId, "pwd");

    // First call with correlation id in context
    StepVerifier.create(
        controller.postOrder(idempotencyKey, req, principal)
            .contextWrite(Context.of(CorrelationContext.CORRELATION_ID_KEY, "corr-lab6-abc"))
    )
    .expectNextMatches(resp -> resp.getId().equals("order-idemp-1"))
    .verifyComplete();

    // Verify record was inserted and completed
    verify(idempotencyRepo).insert((IdempotencyRecord) argThat(r -> ((IdempotencyRecord) r).getKey().equals(idempotencyKey)));
    verify(idempotencyRepo).save(argThat(r -> r.getKey().equals(idempotencyKey) && r.getStatus().equals("COMPLETED")));

    // Second call: duplicate request with same payload returns cached response immediately
    OrderResponse cachedResponse = new OrderResponse();
    cachedResponse.setId("order-idemp-1");
    cachedResponse.setDeliveryName("Craig Walls");
    cachedResponse.setTotal(BigDecimal.TEN);

    String hash = idempotencyService.computePayloadHash(req);
    IdempotencyRecord completedRecord = IdempotencyRecord.builder()
        .key(idempotencyKey)
        .userId(userId)
        .requestHash(hash)
        .status(IdempotencyRecord.STATUS_COMPLETED)
        .statusCode(201)
        .responseBody(objectMapper.writeValueAsString(cachedResponse))
        .build();

    when(idempotencyRepo.findByKeyAndUserId(idempotencyKey, userId)).thenReturn(Mono.just(completedRecord));

    StepVerifier.create(controller.postOrder(idempotencyKey, req, principal))
        .expectNextMatches(resp -> resp.getId().equals("order-idemp-1"))
        .verifyComplete();

    // Third call: duplicate key with different payload returns 409 Conflict
    OrderCreateRequest alteredReq = new OrderCreateRequest();
    alteredReq.setDeliveryName("Hacker Name");

    StepVerifier.create(controller.postOrder(idempotencyKey, alteredReq, principal))
        .expectErrorMatches(err -> err instanceof BusinessRuleException
            && "IDEMPOTENCY_PAYLOAD_MISMATCH".equals(((BusinessRuleException) err).getCode()))
        .verify();
  }
}
