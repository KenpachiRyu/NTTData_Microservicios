package tacos.physics;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tacos.Ingredient;
import tacos.Taco;

@Component
public class IngredientCountRule implements TacoPhysicsRule {

  public static final int MIN_INGREDIENTS = 2;
  public static final int MAX_INGREDIENTS = 12;

  @Override
  public List<TacoViolation> evaluate(Taco taco, List<Ingredient> ingredients) {
    List<TacoViolation> violations = new ArrayList<>();
    int count = ingredients != null ? ingredients.size() : 0;

    if (count < MIN_INGREDIENTS || count > MAX_INGREDIENTS) {
      violations.add(new TacoViolation(
          "INVALID_INGREDIENT_COUNT",
          "El diseño debe contener entre " + MIN_INGREDIENTS + " y " + MAX_INGREDIENTS + " ingredientes. Total actual: " + count,
          "ingredients.size"
      ));
    }
    return violations;
  }

  @Override
  public String getRuleName() {
    return "IngredientCountRule";
  }
}
