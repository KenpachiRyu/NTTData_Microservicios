package tacos.web.api;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.Taco;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemRequest {

  @NotNull(message = "El diseño del taco es obligatorio")
  @Valid
  private Taco taco;

  @Min(value = 1, message = "La cantidad mínima por línea es 1")
  @Max(value = 50, message = "La cantidad máxima por línea es 50")
  private int quantity = 1;
}
