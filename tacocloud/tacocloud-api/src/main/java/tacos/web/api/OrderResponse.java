package tacos.web.api;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import lombok.Data;
import tacos.OrderItem;
import tacos.Taco;

@Data
public class OrderResponse {

  private String id;
  private Date placedAt;
  private String deliveryName;
  private String deliveryStreet;
  private String deliveryCity;
  private String deliveryState;
  private String deliveryZip;
  private String paymentToken;
  private List<Taco> tacos;
  private List<OrderItem> items;

  // Campos calculados por el servidor (TC-14 / TC-15)
  private BigDecimal subtotal;
  private BigDecimal discountAmount;
  private BigDecimal total;
  private String couponCode;
  private String currency = "USD";

  // TC-25 / TC-26: Ciclo de vida y cocina
  private Long version;
  private tacos.OrderStatus status;
  private String stationId;
  private String cookId;
  private Integer estimatedPrepMinutes;
  private List<tacos.OrderStatusChange> statusHistory;
}