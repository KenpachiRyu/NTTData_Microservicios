package tacos.web.api;

import javax.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockAdjustmentRequest {

  @NotNull(message = "El monto de ajuste es obligatorio")
  private Integer adjustment;

  private String reason;

  private Long version;
}
