package tacos.web.api;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.OrderItem;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuoteResponse {

  private List<OrderItem> items;
  private BigDecimal subtotal;
  private BigDecimal discountAmount;
  private BigDecimal total;
  private String couponCode;
  private String currency = "USD";
}
