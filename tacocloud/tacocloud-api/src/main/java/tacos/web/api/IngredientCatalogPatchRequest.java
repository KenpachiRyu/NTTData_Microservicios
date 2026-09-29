package tacos.web.api;

import java.math.BigDecimal;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IngredientCatalogPatchRequest {

  @DecimalMin(value = "0.0", inclusive = true, message = "El precio unitario no puede ser negativo")
  private BigDecimal unitPrice;

  private Boolean available;

  @Min(value = 0, message = "El nivel de reorden no puede ser negativo")
  private Integer reorderLevel;

  private Long version;
}
