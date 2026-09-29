package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.OrderItem;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.data.IngredientRepository;

public class PricingServiceTest {

  private IngredientRepository ingredientRepo;
  private CouponService couponService;
  private PricingService pricingService;

  private Ingredient flto;
  private Ingredient grbf;
  private Ingredient ched;

  @BeforeEach
  public void setUp() {
    ingredientRepo = Mockito.mock(IngredientRepository.class);
    couponService = new CouponService(new CouponProperties(), null);
    pricingService = new PricingService(ingredientRepo, couponService);

    flto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, new BigDecimal("0.50"));
    grbf = new Ingredient("GRBF", "Ground Beef", Type.PROTEIN, new BigDecimal("1.50"));
    ched = new Ingredient("CHED", "Cheddar", Type.CHEESE, new BigDecimal("0.50"));

    when(ingredientRepo.findAllById(any(Iterable.class))).thenAnswer(invocation -> {
      Iterable<String> ids = invocation.getArgument(0);
      List<Ingredient> matched = new java.util.ArrayList<>();
      for (String id : ids) {
        if ("FLTO".equals(id)) matched.add(flto);
        if ("GRBF".equals(id)) matched.add(grbf);
        if ("CHED".equals(id)) matched.add(ched);
      }
      return Flux.fromIterable(matched);
    });
  }

  @Test
  public void shouldCalculateTacoPriceCorrectlyFromDatabasePrices() {
    Taco taco = new Taco();
    taco.setName("Beef Supreme");
    taco.setIngredients(Arrays.asList(flto, grbf, ched));

    StepVerifier.create(pricingService.calculateTacoPrice(taco))
        .assertNext(price -> {
          assertEquals(new BigDecimal("2.50"), price);
        })
        .verifyComplete();
  }

  @Test
  public void shouldPriceOrderWithQuantitiesAndHistoricalSnapshots() {
    Taco taco = new Taco();
    taco.setName("Beef Supreme");
    taco.setIngredients(Arrays.asList(flto, grbf, ched));

    OrderItem item = new OrderItem(taco, 2);

    TacoOrder order = new TacoOrder();
    order.setItems(Collections.singletonList(item));

    StepVerifier.create(pricingService.priceOrder(order, null))
        .assertNext(priced -> {
          assertNotNull(priced);
          assertEquals(new BigDecimal("5.00"), priced.getSubtotal());
          assertEquals(new BigDecimal("5.00"), priced.getTotal());
          assertEquals(1, priced.getItems().size());

          OrderItem snapshot = priced.getItems().get(0);
          assertEquals(new BigDecimal("2.50"), snapshot.getUnitPriceAtPurchase());
          assertEquals(new BigDecimal("5.00"), snapshot.getSubtotal());
          assertEquals(2, snapshot.getQuantity());
          assertEquals("USD", priced.getCurrency());
        })
        .verifyComplete();
  }

  @Test
  public void shouldIgnoreClientSentTotalAndComputeReliableServerTotal() {
    Taco taco = new Taco();
    taco.setName("Beef Supreme");
    taco.setIngredients(Arrays.asList(flto, grbf, ched));

    OrderItem item = new OrderItem(taco, 3); // 3 * 2.50 = 7.50

    TacoOrder order = new TacoOrder();
    order.setItems(Collections.singletonList(item));
    order.setTotal(new BigDecimal("0.01")); // Manipulated client total

    StepVerifier.create(pricingService.priceOrder(order, null))
        .assertNext(priced -> {
          assertEquals(new BigDecimal("7.50"), priced.getTotal());
          assertEquals(new BigDecimal("7.50"), priced.getSubtotal());
        })
        .verifyComplete();
  }

  @Test
  public void shouldFailWhenQuantityIsZeroOrNegative() {
    Taco taco = new Taco();
    taco.setName("Beef Supreme");
    taco.setIngredients(Arrays.asList(flto, grbf));

    OrderItem item = new OrderItem(taco, 0);

    TacoOrder order = new TacoOrder();
    order.setItems(Collections.singletonList(item));

    StepVerifier.create(pricingService.priceOrder(order, null))
        .expectErrorMatches(ex -> ex instanceof BusinessRuleException &&
            ex.getMessage().contains("mayor a cero"))
        .verify();
  }

  @Test
  public void shouldFailWhenQuantityExceedsMaximumConfigured() {
    Taco taco = new Taco();
    taco.setName("Beef Supreme");
    taco.setIngredients(Arrays.asList(flto, grbf));

    OrderItem item = new OrderItem(taco, 55);

    TacoOrder order = new TacoOrder();
    order.setItems(Collections.singletonList(item));

    StepVerifier.create(pricingService.priceOrder(order, null))
        .expectErrorMatches(ex -> ex instanceof BusinessRuleException &&
            ex.getMessage().contains("límite máximo"))
        .verify();
  }
}
