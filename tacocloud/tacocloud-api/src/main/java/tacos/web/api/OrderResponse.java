package tacos.web.api;

import java.util.Date;
import java.util.List;
import lombok.Data;
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
}