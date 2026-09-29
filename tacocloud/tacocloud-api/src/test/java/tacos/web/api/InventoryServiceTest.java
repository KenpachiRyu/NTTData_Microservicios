package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.mongodb.client.result.UpdateResult;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.InventoryReservation;
import tacos.InventoryReservation.ReservationStatus;
import tacos.data.InventoryReservationRepository;

public class InventoryServiceTest {

  private ReactiveMongoTemplate mongoTemplate;
  private InventoryReservationRepository reservationRepo;
  private InventoryService inventoryService;

  @BeforeEach
  public void setUp() {
    mongoTemplate = Mockito.mock(ReactiveMongoTemplate.class);
    reservationRepo = Mockito.mock(InventoryReservationRepository.class);
    inventoryService = new InventoryService(mongoTemplate, reservationRepo);
  }

  @Test
  public void shouldReserveInventorySuccessfullyWhenStockIsSufficient() {
    Map<String, Integer> requested = new HashMap<>();
    requested.put("FLTO", 2);
    requested.put("GRBF", 1);

    Ingredient updatedFlto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);
    updatedFlto.setStockOnHand(98);

    Ingredient updatedGrbf = new Ingredient("GRBF", "Ground Beef", Type.PROTEIN);
    updatedGrbf.setStockOnHand(49);

    when(reservationRepo.findByIdempotencyKey("idem-123")).thenReturn(Mono.empty());
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Ingredient.class)))
        .thenReturn(Mono.just(updatedFlto))
        .thenReturn(Mono.just(updatedGrbf));

    when(reservationRepo.save(any(InventoryReservation.class))).thenAnswer(inv -> {
      InventoryReservation r = inv.getArgument(0);
      r.setId("res-1");
      return Mono.just(r);
    });

    StepVerifier.create(inventoryService.reserve("order-1", "idem-123", requested))
        .assertNext(reservation -> {
          assertNotNull(reservation);
          assertEquals("order-1", reservation.getOrderId());
          assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
          assertEquals(2, reservation.getReservedQuantities().size());
        })
        .verifyComplete();

    verify(mongoTemplate, times(2)).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Ingredient.class));
    verify(reservationRepo).save(any(InventoryReservation.class));
  }

  @Test
  public void shouldFailAndCompensateWhenOneIngredientHasInsufficientStock() {
    Map<String, Integer> requested = new HashMap<>();
    requested.put("FLTO", 1);
    requested.put("GRBF", 1);

    Ingredient updatedFlto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);
    updatedFlto.setStockOnHand(10);

    when(reservationRepo.findByIdempotencyKey(any())).thenReturn(Mono.empty());

    // FLTO succeed, then GRBF fails (returns empty Mono -> condition stockOnHand >= qty failed)
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Ingredient.class)))
        .thenReturn(Mono.just(updatedFlto))
        .thenReturn(Mono.empty());

    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Ingredient.class)))
        .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));

    StepVerifier.create(inventoryService.reserve("order-2", "idem-456", requested))
        .expectErrorMatches(ex -> {
          assertTrue(ex instanceof BusinessRuleException);
          BusinessRuleException bre = (BusinessRuleException) ex;
          assertEquals("INSUFFICIENT_STOCK", bre.getCode());
          return bre.getMessage().contains("Stock insuficiente");
        })
        .verify();

    // Verifies rollback/compensation occurred for FLTO!
    verify(mongoTemplate, times(1)).updateFirst(any(Query.class), any(Update.class), eq(Ingredient.class));
    verify(reservationRepo, never()).save(any(InventoryReservation.class));
  }

  @Test
  public void shouldNotReserveTwiceWhenIdempotencyKeyMatchesConfirmedReservation() {
    Map<String, Integer> requested = new HashMap<>();
    requested.put("FLTO", 2);

    InventoryReservation existing = new InventoryReservation(
        "res-existing", "order-1", "idem-duplicate", requested, ReservationStatus.CONFIRMED, new Date()
    );

    when(reservationRepo.findByIdempotencyKey("idem-duplicate")).thenReturn(Mono.just(existing));

    StepVerifier.create(inventoryService.reserve("order-1", "idem-duplicate", requested))
        .assertNext(res -> {
          assertEquals("res-existing", res.getId());
          assertEquals(ReservationStatus.CONFIRMED, res.getStatus());
        })
        .verifyComplete();

    // No Mongo modifications should be made on idempotent retry
    verify(mongoTemplate, never()).findAndModify(any(), any(), any(), eq(Ingredient.class));
  }

  @Test
  public void shouldReleaseReservationIdempotently() {
    Map<String, Integer> reserved = new HashMap<>();
    reserved.put("FLTO", 2);

    InventoryReservation reservation = new InventoryReservation(
        "res-1", "order-1", "idem-1", reserved, ReservationStatus.CONFIRMED, new Date()
    );

    when(reservationRepo.findById("res-1")).thenReturn(Mono.just(reservation));
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Ingredient.class)))
        .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
    when(reservationRepo.save(any(InventoryReservation.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    // First release call -> releases stock
    StepVerifier.create(inventoryService.release("res-1"))
        .assertNext(rel -> {
          assertEquals(ReservationStatus.RELEASED, rel.getStatus());
        })
        .verifyComplete();

    verify(mongoTemplate, times(1)).updateFirst(any(Query.class), any(Update.class), eq(Ingredient.class));

    // Second release call on already released reservation -> does not touch Mongo stock
    reservation.setStatus(ReservationStatus.RELEASED);
    when(reservationRepo.findById("res-1")).thenReturn(Mono.just(reservation));

    StepVerifier.create(inventoryService.release("res-1"))
        .assertNext(rel -> {
          assertEquals(ReservationStatus.RELEASED, rel.getStatus());
        })
        .verifyComplete();

    // Total executions remain 1
    verify(mongoTemplate, times(1)).updateFirst(any(Query.class), any(Update.class), eq(Ingredient.class));
  }
}
