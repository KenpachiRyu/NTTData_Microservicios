package tacos.web.api;

import org.springframework.stereotype.Component;
import tacos.Ingredient;

@Component
public class IngredientMapper {

  public Ingredient toDomain(IngredientRequest request) {
    if (request == null) {
      return null;
    }
    return new Ingredient(request.getId(), request.getName(), request.getType());
  }

  public IngredientResponse toResponse(Ingredient ingredient) {
    if (ingredient == null) {
      return null;
    }
    return new IngredientResponse(ingredient.getId(), ingredient.getName(), ingredient.getType());
  }

}
