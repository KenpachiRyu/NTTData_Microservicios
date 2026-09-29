package tacos.web.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.InventoryReservation;
import tacos.InventoryReservation.ReservationStatus;
import tacos.data.InventoryReservationRepository;

@Service
public class InventoryService {

  private final ReactiveMongoTemplate mongoTemplate;
  private final InventoryReservationRepository reservationRepo;

  @Autowired
  public InventoryService(ReactiveMongoTemplate mongoTemplate,
                          InventoryReservationRepository reservationRepo) {
    this.mongoTemplate = mongoTemplate;
    this.reservationRepo = reservationRepo;
  }

  public Mono<InventoryReservation> reserve(String orderId, String idempotencyKey, Map<String, Integer> requestedQuantities) {
    if (requestedQuantities == null || requestedQuantities.isEmpty()) {
      InventoryReservation emptyReservation = new InventoryReservation(
          null, orderId, idempotencyKey, Collections.emptyMap(), ReservationStatus.CONFIRMED, new Date());
      return reservationRepo.save(emptyReservation);
    }

    // Validar cantidades no negativas
    for (Map.Entry<String, Integer> entry : requestedQuantities.entrySet()) {
      if (entry.getValue() == null || entry.getValue() <= 0) {
        return Mono.error(new BusinessRuleException("Cantidad inválida para reservar el ingrediente " + entry.getKey()));
      }
    }

    // Si hay clave de idempotencia, verificar si ya fue procesada
    Mono<InventoryReservation> checkExisting = idempotencyKey != null && !idempotencyKey.trim().isEmpty()
        ? reservationRepo.findByIdempotencyKey(idempotencyKey)
        : Mono.empty();

    return checkExisting.flatMap(existing -> {
      if (existing.getStatus() == ReservationStatus.CONFIRMED || existing.getStatus() == ReservationStatus.PENDING) {
        return Mono.just(existing);
      }
      return executeReservation(orderId, idempotencyKey, requestedQuantities);
    }).switchIfEmpty(Mono.defer(() -> executeReservation(orderId, idempotencyKey, requestedQuantities)));
  }

  private Mono<InventoryReservation> executeReservation(String orderId, String idempotencyKey, Map<String, Integer> requestedQuantities) {
    // Ordenar ingredientes alfabéticamente para orden determinista
    Map<String, Integer> sortedRequests = new TreeMap<>(requestedQuantities);
    List<Map.Entry<String, Integer>> entries = new ArrayList<>(sortedRequests.entrySet());

    List<Map.Entry<String, Integer>> successfullyReserved = new ArrayList<>();

    return Flux.fromIterable(entries)
        .concatMap(entry -> {
          String ingredientId = entry.getKey();
          int qty = entry.getValue();

          Query query = Query.query(Criteria.where("id").is(ingredientId).and("stockOnHand").gte(qty));
          Update update = new Update().inc("stockOnHand", -qty);

          return mongoTemplate.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), Ingredient.class)
              .flatMap(updatedIngredient -> {
                successfullyReserved.add(entry);
                return Mono.just(updatedIngredient);
              })
              .switchIfEmpty(Mono.error(new BusinessRuleException(
                  "INSUFFICIENT_STOCK",
                  "Stock insuficiente para el ingrediente: " + ingredientId + " (solicitado: " + qty + ")")));
        })
        .collectList()
        .flatMap(results -> {
          InventoryReservation reservation = new InventoryReservation(
              null,
              orderId,
              idempotencyKey,
              requestedQuantities,
              ReservationStatus.CONFIRMED,
              new Date()
          );
          return reservationRepo.save(reservation);
        })
        .onErrorResume(error -> {
          // Compensación verificable: liberar lo que se reservó parcialmente antes del fallo
          if (!successfullyReserved.isEmpty()) {
            return Flux.fromIterable(successfullyReserved)
                .concatMap(entry -> {
                  Query compQuery = Query.query(Criteria.where("id").is(entry.getKey()));
                  Update compUpdate = new Update().inc("stockOnHand", entry.getValue());
                  return mongoTemplate.updateFirst(compQuery, compUpdate, Ingredient.class);
                })
                .then(Mono.error(error));
          }
          return Mono.error(error);
        });
  }

  public Mono<InventoryReservation> release(String reservationId) {
    if (reservationId == null || reservationId.trim().isEmpty()) {
      return Mono.empty();
    }

    return reservationRepo.findById(reservationId)
        .flatMap(reservation -> {
          if (reservation.getStatus() == ReservationStatus.RELEASED) {
            // Ya liberada previamente: idempotencia garantizada
            return Mono.just(reservation);
          }

          Map<String, Integer> reserved = reservation.getReservedQuantities();
          if (reserved == null || reserved.isEmpty()) {
            reservation.setStatus(ReservationStatus.RELEASED);
            return reservationRepo.save(reservation);
          }

          return Flux.fromIterable(reserved.entrySet())
              .concatMap(entry -> {
                Query query = Query.query(Criteria.where("id").is(entry.getKey()));
                Update update = new Update().inc("stockOnHand", entry.getValue());
                return mongoTemplate.updateFirst(query, update, Ingredient.class);
              })
              .then(Mono.defer(() -> {
                reservation.setStatus(ReservationStatus.RELEASED);
                return reservationRepo.save(reservation);
              }));
        });
  }
}
