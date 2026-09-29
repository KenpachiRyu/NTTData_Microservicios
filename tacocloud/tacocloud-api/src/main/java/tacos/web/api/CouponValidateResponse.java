package tacos.web.api;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CouponValidateResponse {

  private boolean valid;
  private String code;
  private BigDecimal discountAmount = BigDecimal.ZERO;
  private BigDecimal finalTotal = BigDecimal.ZERO;
  private String message;
}
