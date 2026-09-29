package tacos.web.api;

import java.math.BigDecimal;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CouponValidateRequest {

  @NotBlank(message = "El código de cupón es obligatorio")
  private String code;

  @NotNull(message = "El subtotal es obligatorio")
  @DecimalMin(value = "0.01", message = "El subtotal debe ser mayor a cero")
  private BigDecimal subtotal;
}
