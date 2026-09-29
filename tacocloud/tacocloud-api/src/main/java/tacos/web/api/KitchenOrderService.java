package tacos.web.api;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.OrderItem;
import tacos.OrderStatus;
import tacos.OrderStatusChange;
import tacos.Taco;
import tacos.TacoOrder;

@Service
@RequiredArgsConstructor
public class KitchenOrderService {

  private final ReactiveMongoTemplate mongoTemplate;
  private final OrderStateService stateService;

  public Flux<KitchenOrderDto> getQueue() {
    Query query = new Query();
    query.addCriteria(new Criteria().orOperator(
        Criteria.where("status").is(OrderStatus.CREATED),
        Criteria.where("status").is(OrderStatus.CREATED.name())
    ));
    query.with(Sort.by(Sort.Direction.ASC, "placedAt", "id"));

    AtomicInteger positionInQueue = new AtomicInteger(0);

    return mongoTemplate.find(query, TacoOrder.class)
        .map(order -> {
          int pos = positionInQueue.getAndIncrement();
          int eta = calculateEta(order, pos);
          order.setEstimatedPrepMinutes(eta);
          KitchenOrderDto dto = toKitchenDto(order);
          dto.setEstimatedPrepMinutes(eta);
          return dto;
        });
  }

  public Mono<KitchenOrderDto> claimNext(String stationId, String cookId, Authentication auth) {
    String finalCookId = (cookId != null && !cookId.trim().isEmpty())
        ? cookId
        : (auth != null ? auth.getName() : "CHEF_1");

    String finalStation = (stationId != null && !stationId.trim().isEmpty()) ? stationId : "STATION_1";

    // Validar si la estación ya tiene orden activa (ACCEPTED o PREPARING)
    Query activeQuery = new Query();
    activeQuery.addCriteria(Criteria.where("stationId").is(finalStation)
        .and("status").in(OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.ACCEPTED.name(), OrderStatus.PREPARING.name()));

    return mongoTemplate.exists(activeQuery, TacoOrder.class)
        .flatMap(isBusy -> {
          if (isBusy) {
            return Mono.error(new BusinessRuleException("STATION_ALREADY_BUSY",
                "La estación " + finalStation + " ya tiene una orden activa"));
          }

          Query claimQuery = new Query();
          claimQuery.addCriteria(new Criteria().orOperator(
              Criteria.where("status").is(OrderStatus.CREATED),
              Criteria.where("status").is(OrderStatus.CREATED.name())
          ));
          claimQuery.with(Sort.by(Sort.Direction.ASC, "placedAt", "id"));

          int eta = 7; // Base estimate for immediate prep

          Update update = new Update();
          update.set("status", OrderStatus.ACCEPTED);
          update.set("stationId", finalStation);
          update.set("cookId", finalCookId);
          update.set("estimatedPrepMinutes", eta);
          update.push("statusHistory", new OrderStatusChange(
              OrderStatus.CREATED, OrderStatus.ACCEPTED, new Date(), finalCookId, "KITCHEN_CLAIM", "Reclamado por estación " + finalStation
          ));

          FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(true);

          return mongoTemplate.findAndModify(claimQuery, update, options, TacoOrder.class)
              .map(this::toKitchenDto);
        });
  }

  public Mono<KitchenOrderDto> claimById(String orderId, String stationId, String cookId, Authentication auth) {
    String finalCookId = (cookId != null && !cookId.trim().isEmpty())
        ? cookId
        : (auth != null ? auth.getName() : "CHEF_1");

    String finalStation = (stationId != null && !stationId.trim().isEmpty()) ? stationId : "STATION_1";

    Query claimQuery = new Query();
    claimQuery.addCriteria(Criteria.where("id").is(orderId).andOperator(new Criteria().orOperator(
        Criteria.where("status").is(OrderStatus.CREATED),
        Criteria.where("status").is(OrderStatus.CREATED.name())
    )));

    Update update = new Update();
    update.set("status", OrderStatus.ACCEPTED);
    update.set("stationId", finalStation);
    update.set("cookId", finalCookId);
    update.push("statusHistory", new OrderStatusChange(
        OrderStatus.CREATED, OrderStatus.ACCEPTED, new Date(), finalCookId, "KITCHEN_CLAIM", "Reclamado por estación " + finalStation
    ));

    FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(true);

    return mongoTemplate.findAndModify(claimQuery, update, options, TacoOrder.class)
        .map(this::toKitchenDto);
  }

  public Mono<KitchenOrderDto> updateStatus(String orderId, OrderStatus newStatus, String reason, Authentication auth) {
    return stateService.transition(orderId, newStatus, reason, "KITCHEN", auth)
        .map(this::toKitchenDto);
  }

  public int calculateEta(TacoOrder order, int positionInQueue) {
    int eta = 5; // Base 5 minutos
    List<Taco> tacos = (order.getItems() != null && !order.getItems().isEmpty())
        ? order.getItems().stream().map(OrderItem::getTaco).collect(Collectors.toList())
        : (order.getTacos() != null ? order.getTacos() : new ArrayList<>());

    int tacoCount = tacos.size();
    eta += tacoCount * 2; // 2 min por taco

    // Complejidad: tacos con más de 4 ingredientes
    for (Taco taco : tacos) {
      if (taco != null && taco.getIngredients() != null && taco.getIngredients().size() > 4) {
        eta += 1;
      }
    }

    // 3 minutos por cada orden anterior en la cola
    eta += positionInQueue * 3;
    return eta;
  }

  private KitchenOrderDto toKitchenDto(TacoOrder order) {
    if (order == null) return null;
    KitchenOrderDto dto = new KitchenOrderDto();
    dto.setId(order.getId());
    dto.setPlacedAt(order.getPlacedAt());
    dto.setStatus(order.getStatus());
    dto.setStationId(order.getStationId());
    dto.setCookId(order.getCookId());
    dto.setEstimatedPrepMinutes(order.getEstimatedPrepMinutes());
    dto.setDeliveryCity(order.getDeliveryCity());

    List<KitchenTacoDto> tacoDtos = new ArrayList<>();
    List<Taco> tacos = (order.getItems() != null && !order.getItems().isEmpty())
        ? order.getItems().stream().map(OrderItem::getTaco).collect(Collectors.toList())
        : (order.getTacos() != null ? order.getTacos() : new ArrayList<>());

    for (Taco taco : tacos) {
      if (taco != null) {
        List<String> ingNames = new ArrayList<>();
        if (taco.getIngredients() != null) {
          for (Ingredient ing : taco.getIngredients()) {
            if (ing != null) {
              ingNames.add(ing.getName() != null ? ing.getName() : ing.getId());
            }
          }
        }
        tacoDtos.add(new KitchenTacoDto(taco.getName(), ingNames));
      }
    }
    dto.setTacos(tacoDtos);
    return dto;
  }
}
