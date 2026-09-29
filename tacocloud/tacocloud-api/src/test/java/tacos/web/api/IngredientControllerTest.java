package tacos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.data.IngredientRepository;

public class IngredientControllerTest {

  private IngredientRepository ingredientRepo;
  private IngredientController controller;
  private WebTestClient testClient;

  @BeforeEach
  public void setup() {
    ingredientRepo = Mockito.mock(IngredientRepository.class);
    controller = new IngredientController(ingredientRepo);
    testClient = WebTestClient.bindToController(controller).build();
  }

  @Test
  public void shouldUpdateExistingIngredient_Returns200() {
    Ingredient existing = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);
    Ingredient updated = new Ingredient("FLTO", "Flour Tortilla Extra Fine", Type.WRAP);

    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(existing));
    when(ingredientRepo.save(any(Ingredient.class))).thenReturn(Mono.just(updated));

    testClient.put()
        .uri("/api/ingredients/FLTO")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(updated)
        .exchange()
        .expectStatus().isOk()
        .expectBody(Ingredient.class)
        .isEqualTo(updated);

    verify(ingredientRepo).findById("FLTO");
    verify(ingredientRepo).save(updated);
  }

  @Test
  public void shouldReturn404WhenUpdatingNonExistentIngredient() {
    Ingredient ingredientToUpdate = new Ingredient("UNKNOWN", "Unknown Ingredient", Type.SAUCE);

    when(ingredientRepo.findById("UNKNOWN")).thenReturn(Mono.empty());

    testClient.put()
        .uri("/api/ingredients/UNKNOWN")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(ingredientToUpdate)
        .exchange()
        .expectStatus().isNotFound();

    verify(ingredientRepo).findById("UNKNOWN");
    verify(ingredientRepo, never()).save(any(Ingredient.class));
  }

  @Test
  public void shouldReturn400WhenPathIdDoesNotMatchBodyId() {
    Ingredient ingredientToUpdate = new Ingredient("DIFFERENT_ID", "Flour Tortilla", Type.WRAP);

    testClient.put()
        .uri("/api/ingredients/FLTO")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(ingredientToUpdate)
        .exchange()
        .expectStatus().isBadRequest();

    verify(ingredientRepo, never()).findById(any(String.class));
    verify(ingredientRepo, never()).save(any(Ingredient.class));
  }

  @Test
  public void verifySaveFormsPartOfReactiveChainWithStepVerifier() {
    Ingredient existing = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);
    Ingredient updated = new Ingredient("FLTO", "Flour Tortilla Extra Fine", Type.WRAP);

    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(existing));
    when(ingredientRepo.save(updated)).thenReturn(Mono.just(updated));

    Mono<ResponseEntity<Ingredient>> responseMono = controller.updateIngredient("FLTO", updated);

    // Verify repository save was not invoked before subscribing to the returned Mono
    verify(ingredientRepo, never()).save(any());

    StepVerifier.create(responseMono)
        .expectNextMatches(responseEntity -> 
            responseEntity.getStatusCode().is2xxSuccessful() && 
            responseEntity.getBody().equals(updated)
        )
        .verifyComplete();

    // Verify repository save WAS invoked upon subscription
    verify(ingredientRepo).save(updated);
  }

  @Test
  public void shouldDeleteExistingIngredient_Returns204() {
    Ingredient existing = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);

    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(existing));
    when(ingredientRepo.delete(existing)).thenReturn(Mono.empty());

    testClient.delete()
        .uri("/api/ingredients/FLTO")
        .exchange()
        .expectStatus().isNoContent()
        .expectBody().isEmpty();

    verify(ingredientRepo).findById("FLTO");
    verify(ingredientRepo).delete(existing);
  }

  @Test
  public void shouldReturn404WhenDeletingNonExistentIngredient() {
    when(ingredientRepo.findById("UNKNOWN")).thenReturn(Mono.empty());

    testClient.delete()
        .uri("/api/ingredients/UNKNOWN")
        .exchange()
        .expectStatus().isNotFound();

    verify(ingredientRepo).findById("UNKNOWN");
    verify(ingredientRepo, never()).delete(any(Ingredient.class));
  }

  @Test
  public void verifyDeleteFormsPartOfReactiveChainWithStepVerifier() {
    Ingredient existing = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);

    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(existing));
    when(ingredientRepo.delete(existing)).thenReturn(Mono.empty());

    Mono<ResponseEntity<Void>> responseMono = controller.deleteIngredient("FLTO");

    verify(ingredientRepo, never()).delete(any(Ingredient.class));

    StepVerifier.create(responseMono)
        .expectNextMatches(responseEntity -> responseEntity.getStatusCode().is2xxSuccessful())
        .verifyComplete();

    verify(ingredientRepo).delete(existing);
  }

  // =========================================================================
  // TC-03 Tests: Construir Location sin localhost ni rutas rotas
  // =========================================================================

  @Test
  public void shouldCreateIngredient_Returns201WithDynamicLocation() {
    Ingredient toCreate = new Ingredient("CHED", "Cheddar", Type.CHEESE);
    when(ingredientRepo.save(any(Ingredient.class))).thenReturn(Mono.just(toCreate));

    testClient.post()
        .uri("/api/ingredients")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(toCreate)
        .exchange()
        .expectStatus().isCreated()
        .expectHeader().valueEquals("Location", "/api/ingredients/CHED")
        .expectBody(Ingredient.class)
        .isEqualTo(toCreate);

    verify(ingredientRepo).save(any(Ingredient.class));
  }

  @Test
  public void shouldReturn400WhenCreatingIngredientWithMissingName() {
    Ingredient invalid = new Ingredient("CHED", null, Type.CHEESE);

    testClient.post()
        .uri("/api/ingredients")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(invalid)
        .exchange()
        .expectStatus().isBadRequest();

    verify(ingredientRepo, never()).save(any(Ingredient.class));
  }

  @Test
  public void shouldReturn400WhenCreatingIngredientWithMissingType() {
    Ingredient invalid = new Ingredient("CHED", "Cheddar", null);

    testClient.post()
        .uri("/api/ingredients")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(invalid)
        .exchange()
        .expectStatus().isBadRequest();

    verify(ingredientRepo, never()).save(any(Ingredient.class));
  }

  @Test
  public void shouldCreateIngredientWithCustomHostAndPortInLocation() {
    Ingredient toCreate = new Ingredient("BEEF", "Ground Beef", Type.PROTEIN);
    when(ingredientRepo.save(any(Ingredient.class))).thenReturn(Mono.just(toCreate));

    testClient.post()
        .uri("https://api.tacocloud.com:8443/api/ingredients")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(toCreate)
        .exchange()
        .expectStatus().isCreated()
        .expectHeader().valueEquals("Location", "https://api.tacocloud.com:8443/api/ingredients/BEEF")
        .expectBody(Ingredient.class)
        .isEqualTo(toCreate);

    verify(ingredientRepo).save(any(Ingredient.class));
  }
}
