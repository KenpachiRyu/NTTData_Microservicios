package tacos.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.IdempotencyRecord;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.OrderStatus;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.IdempotencyRecordRepository;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;
import tacos.idempotency.IdempotencyService;
import tacos.messaging.OrderMessagingService;
import tacos.messaging.outbox.OutboxService;
import tacos.web.api.EmailOrderService;
import tacos.web.api.IngredientController;
import tacos.web.api.InventoryService;
import tacos.web.api.KitchenApiController;
import tacos.web.api.KitchenClaimRequest;
import tacos.web.api.KitchenOrderDto;
import tacos.web.api.KitchenOrderService;
import tacos.web.api.OrderApiController;
import tacos.web.api.OrderCreateRequest;
import tacos.web.api.OrderItemRequest;
import tacos.web.api.OrderMapper;
import tacos.web.api.OrderPatchRequest;
import tacos.web.api.OrderResponse;
import tacos.web.api.PricingService;
import tacos.web.api.RestExceptionHandler;
import tacos.web.api.correlation.CorrelationIdWebFilter;
import tacos.web.api.versioning.ApiDeprecationFilter;

public class EndToEndHttpApiValidationTest {

  private IngredientRepository ingredientRepo;
  private OrderRepository orderRepo;
  private UserRepository userRepo;
  private PricingService pricingService;
  private InventoryService inventoryService;
  private IdempotencyRecordRepository idempotencyRepo;
  private IdempotencyService idempotencyService;
  private KitchenOrderService kitchenService;
  private ReactiveMongoTemplate mongoTemplate;
  private ObjectMapper objectMapper;

  private IngredientController ingredientController;
  private OrderApiController orderApiController;
  private KitchenApiController kitchenApiController;

  private WebTestClient client;

  @BeforeEach
  void setUp() {
    ingredientRepo = mock(IngredientRepository.class);
    orderRepo = mock(OrderRepository.class);
    userRepo = mock(UserRepository.class);
    pricingService = mock(PricingService.class);
    inventoryService = mock(InventoryService.class);
    idempotencyRepo = mock(IdempotencyRecordRepository.class);
    kitchenService = mock(KitchenOrderService.class);
    mongoTemplate = mock(ReactiveMongoTemplate.class);
    objectMapper = new ObjectMapper();

    idempotencyService = new IdempotencyService(idempotencyRepo, objectMapper);

    ingredientController = new IngredientController(ingredientRepo);
    orderApiController = new OrderApiController(
        orderRepo,
        mock(OrderMessagingService.class),
        mock(EmailOrderService.class),
        ingredientRepo,
        pricingService,
        inventoryService,
        null,
        new OrderMapper(),
        null,
        null,
        null,
        null,
        idempotencyService
    );
    kitchenApiController = new KitchenApiController(kitchenService);

    client = WebTestClient.bindToController(ingredientController, orderApiController, kitchenApiController)
        .controllerAdvice(new RestExceptionHandler())
        .webFilter(new CorrelationIdWebFilter())
        .webFilter(new ApiDeprecationFilter())
        .build();
  }

  // =========================================================================
  // 1. CRUD Reactivo de Ingredientes (TC-01, TC-02, TC-03)
  // =========================================================================
  @Test
  @DisplayName("E2E-01 [TC-01] PUT /api/v1/ingredients/{id} actualiza ingrediente sin perder publisher")
  void testPutIngredientReturns200() {
    Ingredient existing = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, BigDecimal.valueOf(1.00));
    Ingredient updated = new Ingredient("FLTO", "Flour Tortilla Premium", Type.WRAP, BigDecimal.valueOf(1.50));

    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(existing));
    when(ingredientRepo.save(any(Ingredient.class))).thenReturn(Mono.just(updated));

    client.put()
        .uri("/api/v1/ingredients/FLTO")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(updated)
        .exchange()
        .expectStatus().isOk()
        .expectHeader().exists("X-Correlation-Id")
        .expectBody()
        .jsonPath("$.id").isEqualTo("FLTO")
        .jsonPath("$.name").isEqualTo("Flour Tortilla Premium")
        .jsonPath("$.unitPrice").isEqualTo(1.50);
  }

  @Test
  @DisplayName("E2E-02 [TC-03] POST /api/v1/ingredients genera Location header canónico sin localhost")
  void testPostIngredientReturns201WithLocation() {
    Ingredient newIng = new Ingredient("AVOC", "Fresh Avocado", Type.VEGGIES, BigDecimal.valueOf(1.25));

    when(ingredientRepo.save(any(Ingredient.class))).thenReturn(Mono.just(newIng));

    client.post()
        .uri("/api/v1/ingredients")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(newIng)
        .exchange()
        .expectStatus().isCreated()
        .expectHeader().exists("Location")
        .expectHeader().value("Location", loc -> assertThat(loc).endsWith("/api/v1/ingredients/AVOC"))
        .expectBody()
        .jsonPath("$.id").isEqualTo("AVOC");
  }

  @Test
  @DisplayName("E2E-03 [TC-02] DELETE /api/v1/ingredients/{id} existente responde 204 No Content")
  void testDeleteIngredientReturns204() {
    Ingredient existing = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, BigDecimal.valueOf(1.00));

    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(existing));
    when(ingredientRepo.delete(existing)).thenReturn(Mono.empty());

    client.delete()
        .uri("/api/v1/ingredients/FLTO")
        .exchange()
        .expectStatus().isNoContent();
  }

  @Test
  @DisplayName("E2E-04 [TC-02] DELETE /api/v1/ingredients/{id} no existente responde 404 Not Found")
  void testDeleteIngredientReturns404() {
    when(ingredientRepo.findById("UNKNOWN")).thenReturn(Mono.empty());

    client.delete()
        .uri("/api/v1/ingredients/UNKNOWN")
        .exchange()
        .expectStatus().isNotFound();
  }

  // =========================================================================
  // 2. Órdenes con cálculo en servidor (TC-14) e Idempotency-Key (TC-34)
  // =========================================================================
  @Test
  @DisplayName("E2E-05 [TC-14 & TC-34] POST /api/v1/orders calcula precio en servidor e implementa Idempotency-Key")
  void testPostOrderServerPricingAndIdempotencyKey() throws Exception {
    String key = "idemp-client-e2e-101";

    Taco taco = new Taco();
    taco.setName("Carnitas Taco");
    taco.setIngredients(List.of(new Ingredient("CARN", "Carnitas", Type.PROTEIN, BigDecimal.valueOf(4.00))));

    OrderCreateRequest req = new OrderCreateRequest();
    req.setDeliveryName("Craig Walls");
    req.setDeliveryStreet("1234 Main St");
    req.setDeliveryCity("Dallas");
    req.setDeliveryState("TX");
    req.setDeliveryZip("75001");
    req.setPaymentToken("tok_123456");
    req.setClientCalculatedTotal(BigDecimal.valueOf(999.00)); // Cliente envía total fraudulento
    req.setItems(List.of(new OrderItemRequest(taco, 2)));

    TacoOrder serverPricedOrder = new TacoOrder();
    serverPricedOrder.setId("order-srv-1");
    serverPricedOrder.setDeliveryName("Craig Walls");
    serverPricedOrder.setTotal(BigDecimal.valueOf(8.00)); // 2 x 4.00 = 8.00 calculado por servidor
    serverPricedOrder.setSubtotal(BigDecimal.valueOf(8.00));
    serverPricedOrder.setItems(List.of(new tacos.OrderItem(taco, 2)));

    when(pricingService.priceOrder(any(TacoOrder.class), any())).thenReturn(Mono.just(serverPricedOrder));
    when(inventoryService.reserve(any(), any(), any())).thenReturn(Mono.empty());
    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    // Idempotency: primera llamada no encuentra el registro, guarda y completa
    when(idempotencyRepo.findByKeyAndUserId(eq(key), any())).thenReturn(Mono.empty());
    when(idempotencyRepo.insert((IdempotencyRecord) any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(idempotencyRepo.save(any(IdempotencyRecord.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    // 1er request: crea la orden y retorna 201 Created con el total del servidor (8.00)
    client.post()
        .uri("/api/v1/orders")
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(req)
        .exchange()
        .expectStatus().isCreated()
        .expectBody()
        .jsonPath("$.id").isEqualTo("order-srv-1")
        .jsonPath("$.total").isEqualTo(8.00);

    // 2do request idéntico (duplicado secuencial): devuelve 201 Created con la respuesta en caché
    OrderResponse cachedResponse = new OrderResponse();
    cachedResponse.setId("order-srv-1");
    cachedResponse.setDeliveryName("Craig Walls");
    cachedResponse.setTotal(BigDecimal.valueOf(8.00));

    IdempotencyRecord cachedRecord = IdempotencyRecord.builder()
        .key(key)
        .userId("anonymous")
        .requestHash(idempotencyService.computePayloadHash(req))
        .status(IdempotencyRecord.STATUS_COMPLETED)
        .statusCode(201)
        .responseBody(objectMapper.writeValueAsString(cachedResponse))
        .build();

    when(idempotencyRepo.findByKeyAndUserId(eq(key), any())).thenReturn(Mono.just(cachedRecord));

    client.post()
        .uri("/api/v1/orders")
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(req)
        .exchange()
        .expectStatus().isCreated()
        .expectBody()
        .jsonPath("$.id").isEqualTo("order-srv-1")
        .jsonPath("$.total").isEqualTo(8.00);

    // 3er request: misma key con diferente payload -> responde 409 Conflict
    OrderCreateRequest alteredReq = new OrderCreateRequest();
    alteredReq.setDeliveryName("Different Customer");
    alteredReq.setDeliveryStreet("999 Other St");

    client.post()
        .uri("/api/v1/orders")
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(alteredReq)
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code").isEqualTo("IDEMPOTENCY_PAYLOAD_MISMATCH");
  }

  // =========================================================================
  // 3. PATCH sin mutación de ZIP a State (TC-04)
  // =========================================================================
  @Test
  @DisplayName("E2E-06 [TC-04] PATCH /api/v1/orders/{id} actualiza deliveryZip preservando deliveryState intacto")
  void testPatchOrderUpdatesZipWithoutMutatingState() {
    TacoOrder existing = new TacoOrder();
    existing.setId("order-zip-test");
    existing.setDeliveryName("Craig Walls");
    existing.setDeliveryStreet("1234 Main St");
    existing.setDeliveryCity("Dallas");
    existing.setDeliveryState("TX");
    existing.setDeliveryZip("75001");

    when(orderRepo.findById("order-zip-test")).thenReturn(Mono.just(existing));
    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    OrderPatchRequest patch = new OrderPatchRequest();
    patch.setDeliveryZip("75099"); // Solo enviamos zip

    client.patch()
        .uri("/api/v1/orders/order-zip-test")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(patch)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.deliveryZip").isEqualTo("75099")
        .jsonPath("$.deliveryState").isEqualTo("TX"); // deliveryState permanece TX, sin contaminar
  }

  // =========================================================================
  // 4. Cola de cocina y claim atómico (TC-25, TC-26)
  // =========================================================================
  @Test
  @DisplayName("E2E-07 [TC-25 & TC-26] GET y POST claim en /api/v1/kitchen gestionan cola y transición atómica")
  void testKitchenQueueAndClaim() {
    KitchenOrderDto orderDto = new KitchenOrderDto();
    orderDto.setId("kitchen-order-1");
    orderDto.setDeliveryCity("Dallas");
    orderDto.setStatus(OrderStatus.ACCEPTED);

    when(kitchenService.getQueue()).thenReturn(Flux.just(orderDto));

    // 1. Consulta de cola
    client.get()
        .uri("/api/v1/kitchen/queue")
        .exchange()
        .expectStatus().isOk()
        .expectBodyList(KitchenOrderDto.class)
        .hasSize(1);

    // 2. Reclamo de orden por cocinero
    KitchenOrderDto claimedDto = new KitchenOrderDto();
    claimedDto.setId("kitchen-order-1");
    claimedDto.setStatus(OrderStatus.PREPARING);
    claimedDto.setStationId("GRILL-A");
    claimedDto.setCookId("chef-mario");
    claimedDto.setEstimatedPrepMinutes(15);

    when(kitchenService.claimNext(eq("GRILL-A"), eq("chef-mario"), any())).thenReturn(Mono.just(claimedDto));

    KitchenClaimRequest claimReq = new KitchenClaimRequest();
    claimReq.setStationId("GRILL-A");
    claimReq.setCookId("chef-mario");

    client.post()
        .uri("/api/v1/kitchen/orders/claim")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(claimReq)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.id").isEqualTo("kitchen-order-1")
        .jsonPath("$.status").isEqualTo("PREPARING")
        .jsonPath("$.cookId").isEqualTo("chef-mario")
        .jsonPath("$.estimatedPrepMinutes").isEqualTo(15);
  }

  // =========================================================================
  // 5. Problem Details RFC 9457 en pruebas negativas (TC-09)
  // =========================================================================
  @Test
  @DisplayName("E2E-08 [TC-09] PUT con ID de ruta no coincidente retorna 400 Bad Request estructurado")
  void testPutOrderPathMismatchReturnsBadRequest() {
    OrderCreateRequest mismatchReq = new OrderCreateRequest();
    mismatchReq.setId("order-999"); // Diferente a order-100

    client.put()
        .uri("/api/v1/orders/order-100")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(mismatchReq)
        .exchange()
        .expectStatus().isBadRequest();
  }

  // =========================================================================
  // 6. Trazabilidad y RFC 7234 Warning en rutas legacy (TC-31, TC-35)
  // =========================================================================
  @Test
  @DisplayName("E2E-09 [TC-31 & TC-35] Ruta versionada /api/v1/ingredients incluye X-Correlation-Id y omite Warning")
  void testVersionedRouteHasCorrelationIdAndNoWarning() {
    when(ingredientRepo.findAll()).thenReturn(Flux.empty());

    client.get()
        .uri("/api/v1/ingredients")
        .exchange()
        .expectStatus().isOk()
        .expectHeader().exists("X-Correlation-Id")
        .expectHeader().doesNotExist("Warning");
  }

  @Test
  @DisplayName("E2E-10 [TC-35] Ruta legacy sin versión /api/ingredients incluye header RFC 7234 Warning: 299")
  void testLegacyRouteIncludesWarningHeader() {
    when(ingredientRepo.findAll()).thenReturn(Flux.empty());

    client.get()
        .uri("/api/ingredients")
        .exchange()
        .expectStatus().isOk()
        .expectHeader().value("Warning", w -> assertThat(w).contains("299").contains("Deprecated API endpoint"));
  }
}
