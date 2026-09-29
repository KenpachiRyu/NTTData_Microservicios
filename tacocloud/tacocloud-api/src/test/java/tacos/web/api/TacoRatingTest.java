package tacos.web.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Taco;
import tacos.TacoRating;
import tacos.User;
import tacos.data.TacoRatingRepository;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;

public class TacoRatingTest {

  private TacoRatingRepository ratingRepo;
  private TacoRepository tacoRepo;
  private UserRepository userRepo;
  private TacoRatingService ratingService;

  @BeforeEach
  public void setUp() {
    ratingRepo = mock(TacoRatingRepository.class);
    tacoRepo = mock(TacoRepository.class);
    userRepo = mock(UserRepository.class);
    ratingService = new TacoRatingService(ratingRepo, tacoRepo, userRepo);
  }

  @Test
  public void testRateTacoInvalidScoreRejects() {
    StepVerifier.create(ratingService.rateTaco("hector", "taco-1", 6))
        .expectErrorMatches(ex -> ex instanceof BusinessRuleException && ((BusinessRuleException) ex).getErrorCode().equals("INVALID_SCORE"))
        .verify();

    StepVerifier.create(ratingService.rateTaco("hector", "taco-1", 0))
        .expectErrorMatches(ex -> ex instanceof BusinessRuleException && ((BusinessRuleException) ex).getErrorCode().equals("INVALID_SCORE"))
        .verify();
  }

  @Test
  public void testRateTacoUpsertUpdatesScoreWithoutDuplication() {
    User user = new User("hector", "pass", "Hector", "St", "City", "ST", "76000", "555", "hector@example.com");
    user.setId("u-1");
    Taco taco = new Taco();
    taco.setId("t-1");

    when(userRepo.findByUsername("hector")).thenReturn(Mono.just(user));
    when(tacoRepo.findById("t-1")).thenReturn(Mono.just(taco));

    TacoRating existing = new TacoRating("u-1", "t-1", 3);
    existing.setId("r-1");

    when(ratingRepo.findByUserIdAndTacoId("u-1", "t-1")).thenReturn(Mono.just(existing));
    when(ratingRepo.save(any(TacoRating.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(ratingService.rateTaco("hector", "t-1", 5))
        .assertNext(updated -> {
          assertEquals(5, updated.getScore());
          assertEquals("u-1", updated.getUserId());
          assertEquals("t-1", updated.getTacoId());
        })
        .verifyComplete();
  }

  @Test
  public void testRatingSummaryCalculatesAverageCorrectly() {
    Taco taco = new Taco();
    taco.setId("t-1");
    when(tacoRepo.findById("t-1")).thenReturn(Mono.just(taco));

    TacoRating r1 = new TacoRating("u1", "t-1", 5);
    TacoRating r2 = new TacoRating("u2", "t-1", 4);
    when(ratingRepo.findByTacoId("t-1")).thenReturn(Flux.just(r1, r2));

    StepVerifier.create(ratingService.getRatingSummary("t-1", null))
        .assertNext(summary -> {
          assertEquals("t-1", summary.getTacoId());
          assertEquals(2, summary.getTotalVotes());
          assertEquals(4.50, summary.getAverageScore(), 0.001);
          assertNull(summary.getUserScore());
        })
        .verifyComplete();
  }

  @Test
  public void testTopRankingWithMinimumVotesAndTieBreak() {
    Taco taco1 = new Taco(); taco1.setId("t1"); taco1.setName("Taco 1");
    Taco taco2 = new Taco(); taco2.setId("t2"); taco2.setName("Taco 2");

    TacoRating r1 = new TacoRating("u1", "t1", 5);
    TacoRating r2 = new TacoRating("u2", "t1", 5);
    TacoRating r3 = new TacoRating("u3", "t1", 5);
    TacoRating r4 = new TacoRating("u4", "t2", 5);

    when(ratingRepo.findAll()).thenReturn(Flux.just(r1, r2, r3, r4));
    when(tacoRepo.findAllById(any(Iterable.class))).thenReturn(Flux.just(taco1));

    // With minVotes = 2, t2 should be filtered out
    StepVerifier.create(ratingService.getTopTacos(10, 2))
        .assertNext(top -> {
          assertEquals("t1", top.getTaco().getId());
          assertEquals(3, top.getVoteCount());
          assertEquals(5.0, top.getAverageScore(), 0.001);
        })
        .verifyComplete();

    // Verifica que se consultan los tacos del top en una sola operación batch (sin N+1)
    verify(tacoRepo, times(1)).findAllById(any(Iterable.class));
    verify(tacoRepo, never()).findById(anyString());
  }
}
