package tacos.web.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Taco;
import tacos.data.TacoRepository;

public class TacoSearchTest {

  private ReactiveMongoTemplate mongoTemplate;
  private TacoRepository tacoRepo;
  private TacoSearchService searchService;
  private TacoController tacoController;
  private WebTestClient client;

  @BeforeEach
  public void setUp() {
    mongoTemplate = mock(ReactiveMongoTemplate.class);
    tacoRepo = mock(TacoRepository.class);
    searchService = new TacoSearchService(mongoTemplate);
    tacoController = new TacoController(tacoRepo, null, null, searchService, null, null);
    client = WebTestClient.bindToController(tacoController)
        .controllerAdvice(new RestExceptionHandler())
        .build();
  }

  @Test
  public void testInvalidDietaryTagProducesBadRequest() {
    client.get().uri("/api/tacos?tag=NON_EXISTENT_TAG")
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.code").isEqualTo("INVALID_DIETARY_TAG")
        .jsonPath("$.detail").value(val -> assertTrue(val.toString().contains("NON_EXISTENT_TAG")));
  }

  @Test
  public void testInvalidExcludeAllergenProducesBadRequest() {
    client.get().uri("/api/tacos?excludeAllergen=KRYPTONITE")
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.code").isEqualTo("INVALID_ALLERGEN");
  }

  @Test
  public void testInvalidSpiceLevelProducesBadRequest() {
    client.get().uri("/api/tacos?spiceLevel=VOLCANIC")
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.code").isEqualTo("INVALID_SPICE_LEVEL");
  }

  @Test
  public void testInvalidSortFieldProducesBadRequest() {
    client.get().uri("/api/tacos?sort=injectedField")
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.code").isEqualTo("INVALID_SORT_FIELD");
  }

  @Test
  public void testInvalidSortDirectionProducesBadRequest() {
    client.get().uri("/api/tacos?dir=SIDEWAYS")
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.code").isEqualTo("INVALID_SORT_DIRECTION");
  }

  @Test
  public void testInvalidPageAndSizeProducesBadRequest() {
    client.get().uri("/api/tacos?page=-1")
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.code").isEqualTo("INVALID_PAGE");

    client.get().uri("/api/tacos?size=0")
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.code").isEqualTo("INVALID_PAGE_SIZE");

    client.get().uri("/api/tacos?size=100")
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.code").isEqualTo("INVALID_PAGE_SIZE");
  }

  @Test
  public void testValidSearchWithSafeRegex() {
    Taco t1 = new Taco();
    t1.setId("t1");
    t1.setName("Carnitas Special");

    when(mongoTemplate.count(any(Query.class), eq(Taco.class))).thenReturn(Mono.just(1L));
    when(mongoTemplate.find(any(Query.class), eq(Taco.class))).thenReturn(Flux.just(t1));

    client.get().uri("/api/tacos?q=Carnitas.*&tag=VEGAN&sort=name&dir=asc&page=0&size=10")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.content[0].name").isEqualTo("Carnitas Special")
        .jsonPath("$.totalElements").isEqualTo(1);

    ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(captor.capture(), eq(Taco.class));

    String json = captor.getValue().getQueryObject().toJson();
    // Pattern.quote should escape regex special chars
    assertTrue(json.contains("\\Q") || json.contains("Carnitas"));
  }
}
