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
    order.setCouponCode(request.getCouponCode());
    order.setStatus(tacos.OrderStatus.CREATED);
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
    response.setItems(order.getItems());
    response.setSubtotal(order.getSubtotal());
    response.setDiscountAmount(order.getDiscountAmount());
    response.setTotal(order.getTotal());
    response.setCouponCode(order.getCouponCode());
    response.setCurrency(order.getCurrency() != null ? order.getCurrency() : "USD");

    response.setVersion(order.getVersion());
    response.setStatus(order.getStatus());
    response.setStationId(order.getStationId());
    response.setCookId(order.getCookId());
    response.setEstimatedPrepMinutes(order.getEstimatedPrepMinutes());
    response.setStatusHistory(order.getStatusHistory());
    return response;
  }
}
