package tacos.web.api;

import org.springframework.stereotype.Component;
import tacos.TacoOrder;

@Component
public class OrderMapper {

  public TacoOrder toDomain(OrderCreateRequest request) {
    if (request == null) {
      return null;
    }
    TacoOrder order = new TacoOrder();
    order.setDeliveryName(request.getDeliveryName());
    order.setDeliveryStreet(request.getDeliveryStreet());
    order.setDeliveryCity(request.getDeliveryCity());
    order.setDeliveryState(request.getDeliveryState());
    order.setDeliveryZip(request.getDeliveryZip());
    order.setCcNumber(request.getPaymentToken());
    order.setTacos(request.getTacos());
    return order;
  }

  public OrderResponse toResponse(TacoOrder order) {
    if (order == null) {
      return null;
    }
    OrderResponse response = new OrderResponse();
    response.setId(order.getId());
    response.setPlacedAt(order.getPlacedAt());
    response.setDeliveryName(order.getDeliveryName());
    response.setDeliveryStreet(order.getDeliveryStreet());
    response.setDeliveryCity(order.getDeliveryCity());
    response.setDeliveryState(order.getDeliveryState());
    response.setDeliveryZip(order.getDeliveryZip());
    response.setPaymentToken(order.getCcNumber());
    response.setTacos(order.getTacos());
    return response;
  }
}
