package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.data.IngredientRepository;

public class CatalogAndAdminIngredientTest {

  private IngredientRepository ingredientRepo;
  private AdminIngredientController adminController;
  private IngredientController publicController;
  private WebTestClient adminClient;
  private WebTestClient publicClient;

  @BeforeEach
  public void setUp() {
    ingredientRepo = Mockito.mock(IngredientRepository.class);
    adminController = new AdminIngredientController(ingredientRepo);
    publicController = new IngredientController(ingredientRepo);

    adminClient = WebTestClient.bindToController(adminController)
        .controllerAdvice(new RestExceptionHandler())
        .build();

    publicClient = WebTestClient.bindToController(publicController)
        .controllerAdvice(new RestExceptionHandler())
        .build();
  }

  @Test
  public void shouldQueryCatalogWithoutExposingInternalStockMetadata() {
    Ingredient flto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, new BigDecimal("0.50"));
    flto.setAvailable(true);
    flto.setStockOnHand(150);
    flto.setReorderLevel(20);
    flto.setVersion(1L);

    when(ingredientRepo.findAll()).thenReturn(Flux.just(flto));

    publicClient.get()
        .uri("/api/ingredients")
        .exchange()
        .expectStatus().isOk()
        .expectHeader().contentType(MediaType.APPLICATION_JSON)
        .expectBody()
        .jsonPath("$[0].id").isEqualTo("FLTO")
        .jsonPath("$[0].name").isEqualTo("Flour Tortilla")
        .jsonPath("$[0].unitPrice").isEqualTo(0.50)
        .jsonPath("$[0].available").isEqualTo(true)
        .jsonPath("$[0].stockOnHand").doesNotExist()
        .jsonPath("$[0].version").doesNotExist();
  }

  @Test
  public void shouldPatchCatalogByAdminSuccessfully() {
    Ingredient existing = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, new BigDecimal("0.50"));
    existing.setAvailable(true);
    existing.setStockOnHand(100);
    existing.setReorderLevel(10);
    existing.setVersion(1L);

    Ingredient updated = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, new BigDecimal("0.75"));
    updated.setAvailable(false);
    updated.setStockOnHand(100);
    updated.setReorderLevel(25);
    updated.setVersion(2L);

    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(existing));
    when(ingredientRepo.save(any(Ingredient.class))).thenReturn(Mono.just(updated));

    IngredientCatalogPatchRequest request = new IngredientCatalogPatchRequest(
        new BigDecimal("0.75"), false, 25, 1L
    );

    adminClient.patch()
        .uri("/api/admin/ingredients/FLTO/catalog")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus().isOk()
        .expectBody(AdminIngredientResponse.class)
        .value(res -> {
          assertEquals("FLTO", res.getId());
          assertEquals(new BigDecimal("0.75"), res.getUnitPrice());
          assertFalse(res.getAvailable());
          assertEquals(25, res.getReorderLevel());
        });
  }

  @Test
  public void shouldAdjustStockIncrementByAdminSuccessfully() {
    Ingredient existing = new Ingredient("GRBF", "Ground Beef", Type.PROTEIN, new BigDecimal("1.50"));
    existing.setStockOnHand(50);
    existing.setVersion(1L);

    Ingredient updated = new Ingredient("GRBF", "Ground Beef", Type.PROTEIN, new BigDecimal("1.50"));
    updated.setStockOnHand(75);
    updated.setVersion(2L);

    when(ingredientRepo.findById("GRBF")).thenReturn(Mono.just(existing));
    when(ingredientRepo.save(any(Ingredient.class))).thenReturn(Mono.just(updated));

    StockAdjustmentRequest request = new StockAdjustmentRequest(25, "Restock shipment", 1L);

    adminClient.post()
        .uri("/api/admin/ingredients/GRBF/stock-adjustments")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus().isOk()
        .expectBody(AdminIngredientResponse.class)
        .value(res -> {
          assertEquals("GRBF", res.getId());
          assertEquals(75, res.getStockOnHand());
        });
  }

  @Test
  public void shouldRejectStockAdjustmentThatProducesNegativeStockWith422() {
    Ingredient existing = new Ingredient("GRBF", "Ground Beef", Type.PROTEIN, new BigDecimal("1.50"));
    existing.setStockOnHand(10);
    existing.setVersion(1L);

    when(ingredientRepo.findById("GRBF")).thenReturn(Mono.just(existing));

    StockAdjustmentRequest request = new StockAdjustmentRequest(-15, "Defective waste", 1L);

    adminClient.post()
        .uri("/api/admin/ingredients/GRBF/stock-adjustments")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        .expectBody()
        .jsonPath("$.code").isEqualTo("BUSINESS_RULE_VIOLATION")
        .jsonPath("$.detail").value(detail -> assertTrue(((String) detail).contains("negativo")));
  }

  @Test
  public void shouldTranslateOptimisticLockingFailureTo409Conflict() {
    Ingredient existing = new Ingredient("CHED", "Cheddar", Type.CHEESE, new BigDecimal("0.50"));
    existing.setStockOnHand(50);
    existing.setVersion(1L);

    when(ingredientRepo.findById("CHED")).thenReturn(Mono.just(existing));
    when(ingredientRepo.save(any(Ingredient.class))).thenThrow(new OptimisticLockingFailureException("Version conflict"));

    StockAdjustmentRequest request = new StockAdjustmentRequest(10, "Concurrent adjustment", 1L);

    adminClient.post()
        .uri("/api/admin/ingredients/CHED/stock-adjustments")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code").isEqualTo("VERSION_CONFLICT");
  }
}
