package tacos.web.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.InventoryReservation;
import tacos.OrderItem;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;
import tacos.physics.TacoPhysicsValidator;
import tacos.physics.ValidationReport;

public class OrderReorderTest {

  private OrderRepository orderRepo;
  private IngredientRepository ingredientRepo;
  private PricingService pricingService;
  private InventoryService inventoryService;
  private TacoPhysicsValidator physicsValidator;
  private OrderMessagingService orderMessages;
  private OrderMapper orderMapper;
  private ReorderService reorderService;

  @BeforeEach
  public void setUp() {
    orderRepo = mock(OrderRepository.class);
    ingredientRepo = mock(IngredientRepository.class);
    pricingService = mock(PricingService.class);
    inventoryService = mock(InventoryService.class);
    physicsValidator = mock(TacoPhysicsValidator.class);
    orderMessages = mock(OrderMessagingService.class);
    orderMapper = new OrderMapper();

    reorderService = new ReorderService(
        orderRepo,
        ingredientRepo,
        pricingService,
        inventoryService,
        physicsValidator,
        orderMessages,
        orderMapper
    );
  }

  private TacoOrder createSampleOrder(String orderId, String username, BigDecimal total) {
    User user = new User(username, "pass", username, "Street", "City", "ST", "76000", "555", username + "@example.com");
    TacoOrder order = new TacoOrder();
    order.setId(orderId);
    order.setUser(user);
    order.setDeliveryName("Customer " + username);
    order.setDeliveryStreet("Main St 123");
    order.setDeliveryCity("Springfield");
    order.setDeliveryState("IL");
    order.setDeliveryZip("62701");
    order.setPaymentToken("tok_original_123");
    order.setCardBrand("VISA");
    order.setCardLast4("1234");
    order.setPlacedAt(new Date(1000000000L));
    order.setTotal(total);

    Taco taco = new Taco();
    taco.setId("taco-1");
    taco.setName("Carnitas Deluxe");
    Ingredient ing = new Ingredient("FLTO", "Flour Tortilla", Ingredient.Type.WRAP, new BigDecimal("1.50"));
    ing.setAvailable(true);
    taco.setIngredients(Collections.singletonList(ing));

    OrderItem item = new OrderItem(taco, 2);
    order.setItems(Collections.singletonList(item));
    return order;
  }

  @Test
  public void testReorderFailsIfOrderBelongsToAnotherUser() {
    TacoOrder original = createSampleOrder("ord-100", "bob", new BigDecimal("10.00"));
    when(orderRepo.findById("ord-100")).thenReturn(Mono.just(original));

    ReorderRequest req = new ReorderRequest();

    StepVerifier.create(reorderService.reorder("ord-100", "alice", req))
        .expectErrorMatches(ex -> ex instanceof BusinessRuleException &&
            ((BusinessRuleException) ex).getErrorCode().equals("FORBIDDEN_REORDER"))
        .verify();
  }

  @Test
  public void testReorderPriceDifferenceRequiresConfirmation() {
    TacoOrder original = createSampleOrder("ord-100", "alice", new BigDecimal("10.00"));
    when(orderRepo.findById("ord-100")).thenReturn(Mono.just(original));

    when(physicsValidator.validateTaco(any())).thenReturn(Mono.just(ValidationReport.valid()));
    Ingredient ing = new Ingredient("FLTO", "Flour Tortilla", Ingredient.Type.WRAP, new BigDecimal("2.00"));
    when(ingredientRepo.findAllById(any(Iterable.class))).thenReturn(Flux.just(ing));

    // New calculated price is 15.00
    when(pricingService.priceOrder(any(TacoOrder.class), any())).thenAnswer(inv -> {
      TacoOrder ord = inv.getArgument(0);
      ord.setTotal(new BigDecimal("15.00"));
      return Mono.just(ord);
    });

    ReorderRequest reqWithoutConfirmation = new ReorderRequest();
    reqWithoutConfirmation.setConfirmPriceChange(false);

    StepVerifier.create(reorderService.reorder("ord-100", "alice", reqWithoutConfirmation))
        .assertNext(response -> {
          assertFalse(response.isConfirmed());
          assertNull(response.getOrder());
          assertEquals(new BigDecimal("10.00"), response.getOriginalTotal());
          assertEquals(new BigDecimal("15.00"), response.getNewTotal());
          assertEquals(new BigDecimal("5.00"), response.getPriceDifference());
          assertTrue(response.getMessage().contains("confirmPriceChange=true"));
        })
        .verifyComplete();

    // Verify order was NOT saved and inventory was NOT reserved
    verify(orderRepo, never()).save(any());
    verify(inventoryService, never()).reserve(any(), any(), any());
  }

  @Test
  public void testReorderSuccessWithConfirmedPriceChangeAndSaveThenSend() {
    TacoOrder original = createSampleOrder("ord-100", "alice", new BigDecimal("10.00"));
    when(orderRepo.findById("ord-100")).thenReturn(Mono.just(original));

    when(physicsValidator.validateTaco(any())).thenReturn(Mono.just(ValidationReport.valid()));
    Ingredient ing = new Ingredient("FLTO", "Flour Tortilla", Ingredient.Type.WRAP, new BigDecimal("2.00"));
    when(ingredientRepo.findAllById(any(Iterable.class))).thenReturn(Flux.just(ing));

    when(pricingService.priceOrder(any(TacoOrder.class), any())).thenAnswer(inv -> {
      TacoOrder ord = inv.getArgument(0);
      ord.setTotal(new BigDecimal("15.00"));
      return Mono.just(ord);
    });

    InventoryReservation res = new InventoryReservation();
    res.setId("res-99");
    when(inventoryService.reserve(any(), any(), any())).thenReturn(Mono.just(res));

    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(inv -> {
      TacoOrder saved = inv.getArgument(0);
      saved.setId("ord-new-200");
      return Mono.just(saved);
    });

    ReorderRequest req = new ReorderRequest();
    req.setConfirmPriceChange(true);
    req.setPaymentToken("tok_new_card_999");
    req.setIdempotencyKey("idem-custom-key");

    StepVerifier.create(reorderService.reorder("ord-100", "alice", req))
        .assertNext(response -> {
          assertTrue(response.isConfirmed());
          assertNotNull(response.getOrder());
          assertEquals("ord-new-200", response.getOrder().getId());
          assertEquals(new BigDecimal("15.00"), response.getNewTotal());
          assertEquals("tok_new_card_999", response.getOrder().getPaymentToken());
        })
        .verifyComplete();

    // Verify original order was NOT mutated
    assertEquals("ord-100", original.getId());
    assertEquals(new BigDecimal("10.00"), original.getTotal());
    assertEquals("tok_original_123", original.getPaymentToken());

    // Verify idempotencyKey and inventory reservation
    verify(inventoryService).reserve(isNull(), eq("idem-custom-key"), anyMap());

    // Verify event was published after save (Save-then-send)
    verify(orderMessages).sendOrder(any(TacoOrder.class));
  }

  @Test
  public void testReorderFailsIfIngredientNotFoundInCatalog() {
    TacoOrder original = createSampleOrder("ord-100", "alice", new BigDecimal("10.00"));
    when(orderRepo.findById("ord-100")).thenReturn(Mono.just(original));
    when(physicsValidator.validateTaco(any())).thenReturn(Mono.just(ValidationReport.valid()));

    // Returns empty, meaning FLTO was deleted from catalog
    when(ingredientRepo.findAllById(any(Iterable.class))).thenReturn(Flux.empty());

    ReorderRequest req = new ReorderRequest();
    req.setConfirmPriceChange(true);

    StepVerifier.create(reorderService.reorder("ord-100", "alice", req))
        .expectErrorMatches(ex -> ex instanceof BusinessRuleException &&
            ((BusinessRuleException) ex).getErrorCode().equals("INGREDIENT_NOT_FOUND"))
        .verify();

    verify(orderRepo, never()).save(any());
  }

  @Test
  public void testReorderFailsIfIngredientUnavailable() {
    TacoOrder original = createSampleOrder("ord-100", "alice", new BigDecimal("10.00"));
    when(orderRepo.findById("ord-100")).thenReturn(Mono.just(original));
    when(physicsValidator.validateTaco(any())).thenReturn(Mono.just(ValidationReport.valid()));

    Ingredient unavailableIng = new Ingredient("FLTO", "Flour Tortilla", Ingredient.Type.WRAP);
    unavailableIng.setAvailable(false);
    when(ingredientRepo.findAllById(any(Iterable.class))).thenReturn(Flux.just(unavailableIng));

    ReorderRequest req = new ReorderRequest();
    req.setConfirmPriceChange(true);

    StepVerifier.create(reorderService.reorder("ord-100", "alice", req))
        .expectErrorMatches(ex -> ex instanceof BusinessRuleException &&
            ((BusinessRuleException) ex).getErrorCode().equals("INGREDIENT_UNAVAILABLE"))
        .verify();

    verify(orderRepo, never()).save(any());
  }

  @Test
  public void testReorderCompensatesInventoryWhenSaveFails() {
    TacoOrder original = createSampleOrder("ord-100", "alice", new BigDecimal("10.00"));
    when(orderRepo.findById("ord-100")).thenReturn(Mono.just(original));
    when(physicsValidator.validateTaco(any())).thenReturn(Mono.just(ValidationReport.valid()));

    Ingredient ing = new Ingredient("FLTO", "Flour Tortilla", Ingredient.Type.WRAP, new BigDecimal("1.50"));
    ing.setAvailable(true);
    when(ingredientRepo.findAllById(any(Iterable.class))).thenReturn(Flux.just(ing));

    when(pricingService.priceOrder(any(TacoOrder.class), any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    InventoryReservation res = new InventoryReservation();
    res.setId("res-rollback-123");
    when(inventoryService.reserve(any(), any(), any())).thenReturn(Mono.just(res));
    when(inventoryService.release("res-rollback-123")).thenReturn(Mono.just(res));

    // Simulate database failure during order save
    when(orderRepo.save(any(TacoOrder.class))).thenReturn(Mono.error(new RuntimeException("MongoDB connection dropped")));

    ReorderRequest req = new ReorderRequest();
    req.setConfirmPriceChange(true);

    StepVerifier.create(reorderService.reorder("ord-100", "alice", req))
        .expectErrorMessage("MongoDB connection dropped")
        .verify();

    // Verify rollback compensation was called via inventoryService.release
    verify(inventoryService).release("res-rollback-123");
    // Verify event was NOT sent because save failed
    verify(orderMessages, never()).sendOrder(any(TacoOrder.class));
  }
}
