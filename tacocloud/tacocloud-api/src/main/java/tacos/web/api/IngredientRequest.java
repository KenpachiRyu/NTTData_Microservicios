package tacos.web.api;

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

}
