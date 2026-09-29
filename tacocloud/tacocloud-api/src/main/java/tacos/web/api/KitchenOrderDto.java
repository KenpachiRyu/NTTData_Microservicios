package tacos.web.api;

import java.util.Date;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.OrderStatus;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KitchenOrderDto {
  private String id;
  private Date placedAt;
  private OrderStatus status;
  private String stationId;
  private String cookId;
  private Integer estimatedPrepMinutes;
  private List<KitchenTacoDto> tacos;
  private String deliveryCity;
}
