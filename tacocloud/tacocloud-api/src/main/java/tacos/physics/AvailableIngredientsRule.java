package tacos.physics;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tacos.Ingredient;
import tacos.Taco;

@Component
public class AvailableIngredientsRule implements TacoPhysicsRule {

  @Override
  public List<TacoViolation> evaluate(Taco taco, List<Ingredient> ingredients) {
    List<TacoViolation> violations = new ArrayList<>();
    if (ingredients == null) {
      return violations;
    }

    for (Ingredient ing : ingredients) {
      if (ing.getAvailable() != null && !ing.getAvailable()) {
        violations.add(new TacoViolation(
            "UNAVAILABLE_INGREDIENT",
            "El ingrediente '" + ing.getName() + "' (" + ing.getId() + ") no se encuentra disponible actualmente para la venta",
            "ingredients." + ing.getId()
        ));
      }
    }
    return violations;
  }

  @Override
  public String getRuleName() {
    return "AvailableIngredientsRule";
  }
}
