package tacos.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.IdempotencyRecord;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.OrderStatus;
import tacos.Taco;
import tacos.TacoFavorite;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.IdempotencyRecordRepository;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;
import tacos.idempotency.IdempotencyService;
import tacos.web.api.ApiProblem;
import tacos.web.api.CouponProperties;
import tacos.web.api.CouponService;
import tacos.web.api.FavoriteService;
import tacos.web.api.IngredientController;
import tacos.web.api.KitchenApiController;
import tacos.web.api.KitchenClaimRequest;
import tacos.web.api.KitchenOrderDto;
import tacos.web.api.KitchenOrderService;
import tacos.web.api.OrderApiController;
import tacos.web.api.OrderCreateRequest;
import tacos.web.api.OrderMapper;
import tacos.web.api.OrderPatchRequest;
import tacos.web.api.OrderResponse;
import tacos.web.api.PricingService;
import tacos.web.api.RestExceptionHandler;
import tacos.web.api.TacoController;
import tacos.web.api.TacoOfTheDayResponse;
import tacos.web.api.TacoOfTheDayService;
import tacos.web.api.TacoSearchService;
import tacos.web.api.UserFavoritesController;
import tacos.web.api.correlation.CorrelationIdWebFilter;

public class HttpLiveRequestsVerificationTest {

  private WebTestClient client;

  private IngredientRepository ingredientRepo;
  private OrderRepository orderRepo;
  private TacoRepository tacoRepo;
  private UserRepository userRepo;
  private IdempotencyRecordRepository idempotencyRepo;
  private KitchenOrderService kitchenService;
  private TacoOfTheDayService tacoOfTheDayService;
  private TacoSearchService tacoSearchService;
  private FavoriteService favoriteService;

  private PricingService pricingService;
  private CouponService couponService;
  private IdempotencyService idempotencyService;
  private ObjectMapper objectMapper;

  private final UsernamePasswordAuthenticationToken craigAuth =
      new UsernamePasswordAuthenticationToken("craig", "password", Collections.emptyList());

  private final UsernamePasswordAuthenticationToken adminAuth =
      new UsernamePasswordAuthenticationToken("admin", "password", Collections.emptyList());

  @BeforeEach
  void setUp() {
    ingredientRepo = mock(IngredientRepository.class);
    orderRepo = mock(OrderRepository.class);
    tacoRepo = mock(TacoRepository.class);
    userRepo = mock(UserRepository.class);
    idempotencyRepo = mock(IdempotencyRecordRepository.class);
    kitchenService = mock(KitchenOrderService.class);
    tacoOfTheDayService = mock(TacoOfTheDayService.class);
    tacoSearchService = mock(TacoSearchService.class);
    favoriteService = mock(FavoriteService.class);

    objectMapper = new ObjectMapper();
    idempotencyService = new IdempotencyService(idempotencyRepo, objectMapper);

    when(idempotencyRepo.findByKeyAndUserId(any(), any())).thenReturn(Mono.empty());
    when(idempotencyRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(idempotencyRepo.insert(any(IdempotencyRecord.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    CouponProperties couponProps = new CouponProperties();
    couponProps.init();
    couponService = new CouponService(couponProps, null);
    pricingService = new PricingService(ingredientRepo, couponService);

    IngredientController ingredientController = new IngredientController(ingredientRepo);
    OrderApiController orderApiController = new OrderApiController(
        orderRepo,
        null,
        null,
        ingredientRepo,
        pricingService,
        null,
        null,
        new OrderMapper(),
        null,
        null,
        null,
        null,
        idempotencyService
    );
    KitchenApiController kitchenApiController = new KitchenApiController(kitchenService);
    TacoController tacoController = new TacoController(
        tacoRepo,
        null,
        null,
        tacoSearchService,
        tacoOfTheDayService,
        null
    );
    UserFavoritesController favoritesController = new UserFavoritesController(favoriteService);

    client = WebTestClient.bindToController(
            ingredientController,
            orderApiController,
            kitchenApiController,
            tacoController,
            favoritesController)
        .controllerAdvice(new RestExceptionHandler())
        .webFilter(new CorrelationIdWebFilter())
        .webFilter((exchange, chain) -> chain.filter(exchange.mutate().principal(Mono.just(craigAuth)).build()))
        .build();
  }

  @Test
  @DisplayName("1. POST /api/v1/ingredients -> 201 Created con cabecera Location")
  void testPostIngredient() {
    Ingredient jala = new Ingredient("JALA", "Jalapeño Slices", Type.VEGGIES, new BigDecimal("0.75"));
    when(ingredientRepo.save(any(Ingredient.class))).thenReturn(Mono.just(jala));

    EntityExchangeResult<Ingredient> result = client.post()
        .uri("/api/v1/ingredients")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"id\":\"JALA\",\"name\":\"Jalapeño Slices\",\"type\":\"VEGGIES\",\"unitPrice\":0.75}")
        .exchange()
        .expectStatus().isCreated()
        .expectHeader().valueEquals("Location", "/api/v1/ingredients/JALA")
        .expectBody(Ingredient.class)
        .returnResult();

    assertThat(result.getResponseBody()).isNotNull();
    assertThat(result.getResponseBody().getId()).isEqualTo("JALA");
    System.out.println("[TEST-01] POST /api/v1/ingredients -> Status: 201 CREATED, Body: " + result.getResponseBody());
  }

  @Test
  @DisplayName("2. PUT /api/v1/ingredients/FLTO -> 200 OK")
  void testPutIngredient() {
    Ingredient existing = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, new BigDecimal("0.50"));
    Ingredient updated = new Ingredient("FLTO", "Flour Tortilla Extra Soft", Type.WRAP, new BigDecimal("1.25"));
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(existing));
    when(ingredientRepo.save(any(Ingredient.class))).thenReturn(Mono.just(updated));

    EntityExchangeResult<Ingredient> result = client.put()
        .uri("/api/v1/ingredients/FLTO")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"id\":\"FLTO\",\"name\":\"Flour Tortilla Extra Soft\",\"type\":\"WRAP\",\"unitPrice\":1.25}")
        .exchange()
        .expectStatus().isOk()
        .expectBody(Ingredient.class)
        .returnResult();

    assertThat(result.getResponseBody()).isNotNull();
    assertThat(result.getResponseBody().getName()).isEqualTo("Flour Tortilla Extra Soft");
    System.out.println("[TEST-02] PUT /api/v1/ingredients/FLTO -> Status: 200 OK, Body: " + result.getResponseBody());
  }

  @Test
  @DisplayName("3. DELETE /api/v1/ingredients/COTO -> 204 No Content")
  void testDeleteIngredient() {
    Ingredient existing = new Ingredient("COTO", "Corn Tortilla", Type.WRAP, new BigDecimal("0.60"));
    when(ingredientRepo.findById("COTO")).thenReturn(Mono.just(existing));
    when(ingredientRepo.delete(existing)).thenReturn(Mono.empty());

    client.delete()
        .uri("/api/v1/ingredients/COTO")
        .exchange()
        .expectStatus().isNoContent()
        .expectBody().isEmpty();

    System.out.println("[TEST-03] DELETE /api/v1/ingredients/COTO -> Status: 204 NO_CONTENT, Body: (empty)");
  }

  @Test
  @DisplayName("4. PATCH /api/v1/orders/ORDER-DEMO-001 -> 200 OK manteniendo deliveryState")
  void testPatchOrder() {
    TacoOrder order = new TacoOrder();
    order.setId("ORDER-DEMO-001");
    order.setDeliveryName("Craig Walls");
    order.setDeliveryState("TX");
    order.setDeliveryZip("78701");

    when(orderRepo.findById("ORDER-DEMO-001")).thenReturn(Mono.just(order));
    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    EntityExchangeResult<OrderResponse> result = client.patch()
        .uri("/api/v1/orders/ORDER-DEMO-001")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryZip\":\"90210\"}")
        .exchange()
        .expectStatus().isOk()
        .expectBody(OrderResponse.class)
        .returnResult();

    assertThat(result.getResponseBody()).isNotNull();
    assertThat(result.getResponseBody().getDeliveryZip()).isEqualTo("90210");
    assertThat(result.getResponseBody().getDeliveryState()).isEqualTo("TX");
    System.out.println("[TEST-04] PATCH /api/v1/orders/ORDER-DEMO-001 -> Status: 200 OK, Zip: " + result.getResponseBody().getDeliveryZip() + ", State: " + result.getResponseBody().getDeliveryState());
  }

  @Test
  @DisplayName("5. GET /api/v1/orders (autenticado) -> 200 OK")
  void testGetOrdersAuthenticated() {
    TacoOrder order = new TacoOrder();
    order.setId("ORDER-DEMO-001");
    order.setDeliveryName("Craig Walls");
    order.setTotal(new BigDecimal("12.20"));

    when(orderRepo.findAll()).thenReturn(Flux.just(order));

    EntityExchangeResult<List<OrderResponse>> result = client.get()
        .uri("/api/v1/orders")
        .exchange()
        .expectStatus().isOk()
        .expectBodyList(OrderResponse.class)
        .returnResult();

    assertThat(result.getResponseBody()).isNotEmpty();
    System.out.println("[TEST-05] GET /api/v1/orders -> Status: 200 OK, Count: " + result.getResponseBody().size());
  }

  @Test
  @DisplayName("6. POST /api/v1/orders -> 201 Created con cálculo seguro en servidor")
  void testPostOrderValid() {
    Ingredient flto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, new BigDecimal("0.50"));
    Ingredient carn = new Ingredient("CARN", "Carnitas", Type.PROTEIN, new BigDecimal("1.75"));
    Ingredient tmto = new Ingredient("TMTO", "Tomatoes", Type.VEGGIES, new BigDecimal("0.30"));
    Ingredient jack = new Ingredient("JACK", "Monterrey Jack", Type.CHEESE, new BigDecimal("0.50"));

    when(ingredientRepo.findAllById(any(Iterable.class))).thenReturn(Flux.just(flto, carn, tmto, jack));
    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(inv -> {
      TacoOrder o = inv.getArgument(0);
      o.setId("ORDER-NEW-001");
      return Mono.just(o);
    });

    String json = "{\n" +
        "  \"deliveryName\": \"Craig Walls\",\n" +
        "  \"deliveryStreet\": \"123 Taco Way\",\n" +
        "  \"deliveryCity\": \"Austin\",\n" +
        "  \"deliveryState\": \"TX\",\n" +
        "  \"deliveryZip\": \"78701\",\n" +
        "  \"paymentToken\": \"tok_visa_4242\",\n" +
        "  \"couponCode\": \"TACO10\",\n" +
        "  \"tacos\": [\n" +
        "    {\n" +
        "      \"name\": \"Carnitas Supreme\",\n" +
        "      \"ingredients\": [\n" +
        "        {\"id\": \"FLTO\"},\n" +
        "        {\"id\": \"CARN\"},\n" +
        "        {\"id\": \"TMTO\"},\n" +
        "        {\"id\": \"JACK\"}\n" +
        "      ]\n" +
        "    }\n" +
        "  ]\n" +
        "}";

    EntityExchangeResult<OrderResponse> result = client.post()
        .uri("/api/v1/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .header("Idempotency-Key", "tc-order-idemp-001")
        .header("X-Correlation-Id", "tc-corr-001")
        .bodyValue(json)
        .exchange()
        .expectStatus().isCreated()
        .expectBody(OrderResponse.class)
        .returnResult();

    assertThat(result.getResponseBody()).isNotNull();
    assertThat(result.getResponseBody().getTotal()).isNotNull();
    System.out.println("[TEST-06] POST /api/v1/orders -> Status: 201 CREATED, Total: " + result.getResponseBody().getTotal() + ", Discount: " + result.getResponseBody().getDiscountAmount());
  }

  @Test
  @DisplayName("7. POST /api/v1/orders (inválido) -> 400 Bad Request Problem Details RFC 9457")
  void testPostOrderInvalidRfc9457() {
    String invalidJson = "{\"deliveryName\":\"\",\"deliveryStreet\":\"\",\"tacos\":[]}";

    EntityExchangeResult<ApiProblem> result = client.post()
        .uri("/api/v1/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(invalidJson)
        .exchange()
        .expectStatus().isBadRequest()
        .expectHeader().contentType("application/problem+json")
        .expectBody(ApiProblem.class)
        .returnResult();

    assertThat(result.getResponseBody()).isNotNull();
    assertThat(result.getResponseBody().getStatus()).isEqualTo(400);
    System.out.println("[TEST-07] POST /api/v1/orders (negativo) -> Status: 400 BAD_REQUEST, Problem: " + result.getResponseBody().getTitle() + ", Violations: " + result.getResponseBody().getViolations());
  }

  @Test
  @DisplayName("8. GET /api/v1/kitchen/queue -> 200 OK")
  void testGetKitchenQueue() {
    KitchenOrderDto dto = new KitchenOrderDto();
    dto.setId("ORDER-DEMO-001");
    dto.setStatus(OrderStatus.CREATED);
    dto.setEstimatedPrepMinutes(10);

    when(kitchenService.getQueue()).thenReturn(Flux.just(dto));

    EntityExchangeResult<List<KitchenOrderDto>> result = client.get()
        .uri("/api/v1/kitchen/queue")
        .exchange()
        .expectStatus().isOk()
        .expectBodyList(KitchenOrderDto.class)
        .returnResult();

    assertThat(result.getResponseBody()).isNotEmpty();
    System.out.println("[TEST-08] GET /api/v1/kitchen/queue -> Status: 200 OK, Queue items: " + result.getResponseBody().size());
  }

  @Test
  @DisplayName("9. POST /api/v1/kitchen/orders/{id}/claim -> 200 OK y 409 Conflict si ya fue reclamado")
  void testClaimKitchenOrder() {
    KitchenOrderDto claimedDto = new KitchenOrderDto();
    claimedDto.setId("ORDER-DEMO-001");
    claimedDto.setStatus(OrderStatus.PREPARING);
    claimedDto.setCookId("cook-mario");
    claimedDto.setStationId("STATION-GRILL-1");

    when(kitchenService.claimById(eq("ORDER-DEMO-001"), eq("STATION-GRILL-1"), eq("cook-mario"), any()))
        .thenReturn(Mono.just(claimedDto));

    when(kitchenService.claimById(eq("ORDER-DEMO-001"), eq("STATION-GRILL-2"), eq("cook-luigi"), any()))
        .thenReturn(Mono.error(new tacos.web.api.BusinessRuleException("ORDER_CLAIM_CONFLICT", "Orden ya tomada")));

    // Reclamo exitoso
    EntityExchangeResult<KitchenOrderDto> successResult = client.post()
        .uri("/api/v1/kitchen/orders/ORDER-DEMO-001/claim")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"cookId\":\"cook-mario\",\"stationId\":\"STATION-GRILL-1\",\"estimatedPrepMinutes\":10}")
        .exchange()
        .expectStatus().isOk()
        .expectBody(KitchenOrderDto.class)
        .returnResult();

    assertThat(successResult.getResponseBody()).isNotNull();
    assertThat(successResult.getResponseBody().getStatus()).isEqualTo(OrderStatus.PREPARING);
    System.out.println("[TEST-09A] POST /api/v1/kitchen/orders/ORDER-DEMO-001/claim -> Status: 200 OK, Status: " + successResult.getResponseBody().getStatus());

    // Reclamo concurrente conflictivo
    EntityExchangeResult<ApiProblem> conflictResult = client.post()
        .uri("/api/v1/kitchen/orders/ORDER-DEMO-001/claim")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"cookId\":\"cook-luigi\",\"stationId\":\"STATION-GRILL-2\",\"estimatedPrepMinutes\":15}")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.CONFLICT)
        .expectHeader().contentType("application/problem+json")
        .expectBody(ApiProblem.class)
        .returnResult();

    assertThat(conflictResult.getResponseBody()).isNotNull();
    assertThat(conflictResult.getResponseBody().getStatus()).isEqualTo(409);
    System.out.println("[TEST-09B] POST /api/v1/kitchen/orders/ORDER-DEMO-001/claim (concurrente) -> Status: 409 CONFLICT, Code: " + conflictResult.getResponseBody().getCode());
  }

  @Test
  @DisplayName("10. TC-34: Reintento con Idempotency-Key y payload alterado -> 409 Conflict")
  void testIdempotencyConflict() {
    IdempotencyRecord record = IdempotencyRecord.builder()
        .key("tc-order-idemp-001")
        .userId("anonymous")
        .requestHash("original_hash_123")
        .status(IdempotencyRecord.STATUS_COMPLETED)
        .build();

    when(idempotencyRepo.findByKeyAndUserId(eq("tc-order-idemp-001"), any())).thenReturn(Mono.just(record));

    String alteredJson = "{\n" +
        "  \"deliveryName\": \"Craig Walls Modificado\",\n" +
        "  \"deliveryStreet\": \"999 Altered St\",\n" +
        "  \"deliveryCity\": \"Dallas\",\n" +
        "  \"deliveryState\": \"TX\",\n" +
        "  \"deliveryZip\": \"75001\",\n" +
        "  \"paymentToken\": \"tok_mastercard_9999\",\n" +
        "  \"tacos\": [\n" +
        "    {\n" +
        "      \"name\": \"Veggie Delight\",\n" +
        "      \"ingredients\": [\n" +
        "        {\"id\": \"COTO\"},\n" +
        "        {\"id\": \"TMTO\"},\n" +
        "        {\"id\": \"LETC\"}\n" +
        "      ]\n" +
        "    }\n" +
        "  ]\n" +
        "}";

    EntityExchangeResult<ApiProblem> result = client.post()
        .uri("/api/v1/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .header("Idempotency-Key", "tc-order-idemp-001")
        .header("X-Correlation-Id", "tc-corr-002")
        .bodyValue(alteredJson)
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.CONFLICT)
        .expectHeader().contentType("application/problem+json")
        .expectBody(ApiProblem.class)
        .returnResult();

    assertThat(result.getResponseBody()).isNotNull();
    assertThat(result.getResponseBody().getStatus()).isEqualTo(409);
    System.out.println("[TEST-10] POST /api/v1/orders (mismatch idempotencia) -> Status: 409 CONFLICT, Code: " + result.getResponseBody().getCode());
  }

  @Test
  @DisplayName("11. GET /api/v1/tacos/taco-of-the-day -> 200 OK")
  void testGetTacoOfTheDay() {
    Taco taco = new Taco();
    taco.setId("TACO1");
    taco.setName("Carnivore");
    TacoOfTheDayResponse response = new TacoOfTheDayResponse(taco, java.time.LocalDate.now(), "Taco del día");

    when(tacoOfTheDayService.getTacoOfTheDay()).thenReturn(Mono.just(response));

    EntityExchangeResult<TacoOfTheDayResponse> result = client.get()
        .uri("/api/v1/tacos/taco-of-the-day")
        .exchange()
        .expectStatus().isOk()
        .expectBody(TacoOfTheDayResponse.class)
        .returnResult();

    assertThat(result.getResponseBody()).isNotNull();
    assertThat(result.getResponseBody().getTaco().getId()).isEqualTo("TACO1");
    System.out.println("[TEST-11] GET /api/v1/tacos/taco-of-the-day -> Status: 200 OK, Taco: " + result.getResponseBody().getTaco().getName());
  }

  @Test
  @DisplayName("12. POST /api/v1/users/me/favorites -> 200 OK")
  void testAddFavorite() {
    TacoFavorite fav = new TacoFavorite();
    fav.setUserId("craig");
    fav.setTacoId("TACO1");

    when(favoriteService.addFavorite("craig", "TACO1")).thenReturn(Mono.just(fav));

    EntityExchangeResult<TacoFavorite> result = client.post()
        .uri("/api/v1/users/me/favorites")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"tacoId\":\"TACO1\"}")
        .attribute("org.springframework.security.core.Authentication", craigAuth)
        .exchange()
        .expectStatus().isOk()
        .expectBody(TacoFavorite.class)
        .returnResult();

    assertThat(result.getResponseBody()).isNotNull();
    assertThat(result.getResponseBody().getTacoId()).isEqualTo("TACO1");
    System.out.println("[TEST-12] POST /api/v1/users/me/favorites -> Status: 200 OK, TacoId: " + result.getResponseBody().getTacoId());
  }
}
