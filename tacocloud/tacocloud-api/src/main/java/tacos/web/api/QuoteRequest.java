package tacos.web.api;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.Taco;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuoteRequest {

  private List<OrderItemRequest> items = new ArrayList<>();
  private List<Taco> tacos = new ArrayList<>();
  private String couponCode;
}
