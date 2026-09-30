package tacos.messaging;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import tacos.Ingredient;
import tacos.OrderItem;
import tacos.OrderStatus;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.messaging.event.OrderEvent;
import tacos.messaging.event.OrderEventPayload;
import tacos.messaging.event.OrderEventTaco;
import tacos.messaging.event.OrderEventType;

public class OrderEventMapper {

  public static OrderEvent toOrderCreatedEvent(TacoOrder order) {
    return toOrderEvent(order, OrderEventType.ORDER_CREATED);
  }

  public static OrderEvent toOrderStateChangedEvent(TacoOrder order) {
    return toOrderEvent(order, OrderEventType.ORDER_STATUS_CHANGED);
  }

  public static OrderEvent toOrderCancelledEvent(TacoOrder order) {
    return toOrderEvent(order, OrderEventType.ORDER_CANCELLED);
  }

  public static OrderEvent toOrderEvent(TacoOrder order, OrderEventType type) {
    return toOrderEvent(order, type, null);
  }

  public static OrderEvent toOrderEvent(TacoOrder order, OrderEventType type, String correlationId) {
    if (order == null) return null;

    List<OrderEventTaco> tacoPayloads = new ArrayList<>();
    List<Taco> tacos = (order.getItems() != null && !order.getItems().isEmpty())
        ? order.getItems().stream().map(OrderItem::getTaco).collect(Collectors.toList())
        : (order.getTacos() != null ? order.getTacos() : new ArrayList<>());

    for (Taco taco : tacos) {
      if (taco != null) {
        List<String> ings = new ArrayList<>();
        if (taco.getIngredients() != null) {
          for (Ingredient ing : taco.getIngredients()) {
            if (ing != null) {
              ings.add(ing.getName() != null ? ing.getName() : ing.getId());
            }
          }
        }
        tacoPayloads.add(OrderEventTaco.builder().name(taco.getName()).ingredients(ings).build());
      }
    }

    OrderEventPayload payload = OrderEventPayload.builder()
        .orderId(order.getId())
        .placedAt(order.getPlacedAt())
        .status(order.getStatus() != null ? order.getStatus().name() : OrderStatus.CREATED.name())
        .total(order.getTotal())
        .deliveryCity(order.getDeliveryCity())
        .customerName(order.getDeliveryName())
        .tacos(tacoPayloads)
        .build();

    String resolvedCorrelationId = (correlationId != null && !correlationId.trim().isEmpty())
        ? correlationId
        : UUID.randomUUID().toString();

    return OrderEvent.builder()
        .eventId(UUID.randomUUID().toString())
        .eventType(type)
        .version("1.0")
        .occurredAt(new Date())
        .correlationId(resolvedCorrelationId)
        .payload(payload)
        .build();
  }
}
