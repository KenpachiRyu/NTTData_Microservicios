package tacos.physics;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.Taco;

@Component
public class SingleBaseRule implements TacoPhysicsRule {

  @Override
  public List<TacoViolation> evaluate(Taco taco, List<Ingredient> ingredients) {
    List<TacoViolation> violations = new ArrayList<>();
    if (ingredients == null) {
      violations.add(new TacoViolation("INVALID_BASE_COUNT", "El taco debe tener exactamente una base (wrap/tortilla)"));
      return violations;
    }

    long baseCount = ingredients.stream()
        .filter(ing -> ing.getType() == Type.WRAP)
        .count();

    if (baseCount != 1) {
      violations.add(new TacoViolation(
          "INVALID_BASE_COUNT",
          "El taco debe contener exactamente una base (wrap/tortilla). Bases encontradas: " + baseCount,
          "ingredients.base"
      ));
    }
    return violations;
  }

  @Override
  public String getRuleName() {
    return "SingleBaseRule";
  }
}
