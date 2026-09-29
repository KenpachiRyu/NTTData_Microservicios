package tacos.web.api;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReorderResponse {

  private boolean confirmed;
  private OrderResponse order;
  private BigDecimal originalTotal;
  private BigDecimal newTotal;
  private BigDecimal priceDifference;
  private String message;

}
