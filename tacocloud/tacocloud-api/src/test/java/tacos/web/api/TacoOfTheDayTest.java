package tacos.web.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Taco;
import tacos.TacoOfTheDayConfig;
import tacos.data.TacoOfTheDayRepository;
import tacos.data.TacoRepository;

public class TacoOfTheDayTest {

  private TacoRepository tacoRepo;
  private TacoOfTheDayRepository configRepo;
  private Clock fixedClockDay1;
  private Clock fixedClockDay2;

  @BeforeEach
  public void setUp() {
    tacoRepo = mock(TacoRepository.class);
    configRepo = mock(TacoOfTheDayRepository.class);

    // 2026-10-01 UTC
    Instant day1 = Instant.parse("2026-10-01T12:00:00Z");
    // 2026-10-02 UTC
    Instant day2 = Instant.parse("2026-10-02T12:00:00Z");

    fixedClockDay1 = Clock.fixed(day1, ZoneId.of("UTC"));
    fixedClockDay2 = Clock.fixed(day2, ZoneId.of("UTC"));
  }

  private Taco createTaco(String id, String name, boolean available) {
    Taco t = new Taco();
    t.setId(id);
    t.setName(name);
    Ingredient ing = new Ingredient("ing-" + id, "Ing " + id, Ingredient.Type.PROTEIN);
    ing.setAvailable(available);
    t.setIngredients(Collections.singletonList(ing));
    return t;
  }

  @Test
  public void testDeterministicSameResultOnSameDay() {
    Taco t1 = createTaco("A", "Taco A", true);
    Taco t2 = createTaco("B", "Taco B", true);
    Taco t3 = createTaco("C", "Taco C", true);

    when(configRepo.findByDate(any(LocalDate.class))).thenReturn(Mono.empty());
    // Candidates returned in reverse order to test sorting stability
    when(tacoRepo.findAll()).thenReturn(Flux.just(t3, t1, t2));

    TacoOfTheDayService service = new TacoOfTheDayService(tacoRepo, configRepo, fixedClockDay1);

    Mono<TacoOfTheDayResponse> call1 = service.getTacoOfTheDay();
    Mono<TacoOfTheDayResponse> call2 = service.getTacoOfTheDay();

    TacoOfTheDayResponse res1 = call1.block();
    TacoOfTheDayResponse res2 = call2.block();

    assertNotNull(res1);
    assertNotNull(res2);
    assertEquals(res1.getTaco().getId(), res2.getTaco().getId());
    assertEquals(LocalDate.parse("2026-10-01"), res1.getDate());
  }

  @Test
  public void testUnavailableTacoIsExcluded() {
    Taco availableTaco = createTaco("T1", "Available Taco", true);
    Taco unavailableTaco = createTaco("T2", "Unavailable Taco", false);

    when(configRepo.findByDate(any(LocalDate.class))).thenReturn(Mono.empty());
    when(tacoRepo.findAll()).thenReturn(Flux.just(availableTaco, unavailableTaco));

    TacoOfTheDayService service = new TacoOfTheDayService(tacoRepo, configRepo, fixedClockDay1);

    StepVerifier.create(service.getTacoOfTheDay())
        .assertNext(res -> {
          assertEquals("T1", res.getTaco().getId());
        })
        .verifyComplete();
  }

  @Test
  public void testEmptyCandidatesProducesNotFound() {
    when(configRepo.findByDate(any(LocalDate.class))).thenReturn(Mono.empty());
    when(tacoRepo.findAll()).thenReturn(Flux.empty());

    TacoOfTheDayService service = new TacoOfTheDayService(tacoRepo, configRepo, fixedClockDay1);
    TacoController controller = new TacoController(tacoRepo, null, null, null, service, null);
    WebTestClient client = WebTestClient.bindToController(controller).build();

    client.get().uri("/api/tacos/today")
        .exchange()
        .expectStatus().isNotFound();
  }

  @Test
  public void testAdminOverrideTakesPrecedence() {
    Taco overrideTaco = createTaco("ADMIN_TACO", "Admin Special", true);
    LocalDate date = LocalDate.parse("2026-10-01");
    TacoOfTheDayConfig config = new TacoOfTheDayConfig("cfg1", "ADMIN_TACO", date, "Chef recommendation");

    when(configRepo.findByDate(date)).thenReturn(Mono.just(config));
    when(tacoRepo.findById("ADMIN_TACO")).thenReturn(Mono.just(overrideTaco));

    TacoOfTheDayService service = new TacoOfTheDayService(tacoRepo, configRepo, fixedClockDay1);

    StepVerifier.create(service.getTacoOfTheDay())
        .assertNext(res -> {
          assertEquals("ADMIN_TACO", res.getTaco().getId());
          assertEquals("Chef recommendation", res.getReason());
        })
        .verifyComplete();
  }
}
