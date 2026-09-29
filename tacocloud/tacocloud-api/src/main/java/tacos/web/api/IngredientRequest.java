package tacos.web.api;

import java.math.BigDecimal;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.Ingredient.Type;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IngredientRequest {

  private String id;

  @NotBlank(message = "El nombre del ingrediente es obligatorio")
  private String name;

  @NotNull(message = "El tipo de ingrediente es obligatorio")
  private Type type;

  @DecimalMin(value = "0.0", inclusive = true, message = "El precio unitario no puede ser negativo")
  private BigDecimal unitPrice;

  public IngredientRequest(String id, String name, Type type) {
    this(id, name, type, BigDecimal.ZERO);
  }
}
