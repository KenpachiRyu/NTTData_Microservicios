package tacos.web.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CouponDefinition {

  private String code;
  private DiscountType type = DiscountType.PERCENTAGE;
  private BigDecimal amount = BigDecimal.ZERO;
  private BigDecimal minOrderAmount = BigDecimal.ZERO;
  private BigDecimal maxDiscountAmount;
  private LocalDate validFrom;
  private LocalDate validTo;
}
