package tacos.web.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReorderRequest {

  private String paymentToken;
  private boolean confirmPriceChange;
  private String couponCode;
  private String idempotencyKey;

}
