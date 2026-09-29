package tacos.web.api;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import tacos.Ingredient;

@Component
public class IngredientMapper {

  public Ingredient toDomain(IngredientRequest request) {
    if (request == null) {
      return null;
    }
    Ingredient ingredient = new Ingredient(request.getId(), request.getName(), request.getType());
    if (request.getUnitPrice() != null) {
      ingredient.setUnitPrice(request.getUnitPrice());
    }
    return ingredient;
  }

  public IngredientResponse toResponse(Ingredient ingredient) {
    if (ingredient == null) {
      return null;
    }
    return new IngredientResponse(
        ingredient.getId(),
        ingredient.getName(),
        ingredient.getType(),
        ingredient.getUnitPrice() != null ? ingredient.getUnitPrice() : BigDecimal.ZERO,
        ingredient.getAvailable() != null ? ingredient.getAvailable() : true,
        ingredient.getDietaryTags() != null ? ingredient.getDietaryTags() : java.util.Collections.emptySet(),
        ingredient.getAllergens() != null ? ingredient.getAllergens() : java.util.Collections.emptySet(),
        ingredient.getSpiceLevel() != null ? ingredient.getSpiceLevel() : tacos.SpiceLevel.NONE
    );
  }

}
