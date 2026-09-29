package tacos.web.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.security.Principal;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Taco;
import tacos.TacoFavorite;
import tacos.User;
import tacos.data.TacoFavoriteRepository;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;

public class UserFavoritesTest {

  private TacoFavoriteRepository favoriteRepo;
  private TacoRepository tacoRepo;
  private UserRepository userRepo;
  private FavoriteService favoriteService;
  private UserFavoritesController controller;
  private WebTestClient client;

  @BeforeEach
  public void setUp() {
    favoriteRepo = mock(TacoFavoriteRepository.class);
    tacoRepo = mock(TacoRepository.class);
    userRepo = mock(UserRepository.class);

    favoriteService = new FavoriteService(favoriteRepo, tacoRepo, userRepo);
    controller = new UserFavoritesController(favoriteService);
    client = WebTestClient.bindToController(controller).build();
  }

  @Test
  public void testAddFavoriteIdempotencyAndDuplicateKey() {
    User user = new User("hector", "pass", "Hector", "St", "City", "ST", "76000", "555", "hector@example.com");
    user.setId("user-1");
    Taco taco = new Taco();
    taco.setId("taco-1");

    when(userRepo.findByUsername("hector")).thenReturn(Mono.just(user));
    when(tacoRepo.findById("taco-1")).thenReturn(Mono.just(taco));

    TacoFavorite saved = new TacoFavorite("user-1", "taco-1");
    saved.setId("fav-1");

    // First call saves normally
    when(favoriteRepo.save(any(TacoFavorite.class))).thenReturn(Mono.just(saved));
    // Simulate concurrent or duplicate key
    when(favoriteRepo.findByUserIdAndTacoId("user-1", "taco-1")).thenReturn(Mono.just(saved));

    StepVerifier.create(favoriteService.addFavorite("hector", "taco-1"))
        .assertNext(fav -> {
          assertEquals("user-1", fav.getUserId());
          assertEquals("taco-1", fav.getTacoId());
        })
        .verifyComplete();
  }

  @Test
  public void testAddFavoriteWhenTacoNotFoundProducesError() {
    User user = new User("hector", "pass", "Hector", "St", "City", "ST", "76000", "555", "hector@example.com");
    user.setId("user-1");

    when(userRepo.findByUsername("hector")).thenReturn(Mono.just(user));
    when(tacoRepo.findById("unknown-taco")).thenReturn(Mono.empty());

    StepVerifier.create(favoriteService.addFavorite("hector", "unknown-taco"))
        .expectErrorMatches(ex -> ex instanceof BusinessRuleException && ((BusinessRuleException) ex).getErrorCode().equals("TACO_NOT_FOUND"))
        .verify();
  }

  @Test
  public void testRemoveFavoriteIsIdempotent() {
    User user = new User("hector", "pass", "Hector", "St", "City", "ST", "76000", "555", "hector@example.com");
    user.setId("user-1");

    when(userRepo.findByUsername("hector")).thenReturn(Mono.just(user));
    when(favoriteRepo.deleteByUserIdAndTacoId("user-1", "taco-1")).thenReturn(Mono.empty());

    StepVerifier.create(favoriteService.removeFavorite("hector", "taco-1"))
        .verifyComplete();
  }

  @Test
  public void testUserIsolationOnlyReturnsUserFavorites() {
    User userA = new User("userA", "pass", "User A", "St", "City", "ST", "76000", "555", "a@example.com");
    userA.setId("user-A-id");

    Taco tacoA = new Taco();
    tacoA.setId("taco-A");
    tacoA.setName("Taco A");

    TacoFavorite favA = new TacoFavorite("user-A-id", "taco-A");

    when(userRepo.findByUsername("userA")).thenReturn(Mono.just(userA));
    when(favoriteRepo.countByUserId("user-A-id")).thenReturn(Mono.just(1L));
    when(favoriteRepo.findByUserId(eq("user-A-id"), any(Pageable.class)))
        .thenReturn(Flux.just(favA));
    when(tacoRepo.findAllById(any(Iterable.class))).thenReturn(Flux.just(tacoA));

    StepVerifier.create(favoriteService.getFavorites("userA", 0, 10))
        .assertNext(page -> {
          assertEquals(1, page.getTotalElements());
          assertEquals("taco-A", page.getContent().get(0).getId());
        })
        .verifyComplete();

    // Demuestra que se ejecuta 1 sola consulta batch sin N+1
    verify(tacoRepo, times(1)).findAllById(any(Iterable.class));
    verify(tacoRepo, never()).findById(anyString());
  }
}
