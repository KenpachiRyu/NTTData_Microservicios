package tacos.physics;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.SpiceLevel;
import tacos.Taco;

@Component
public class SpicyRequiresCoolingRule implements TacoPhysicsRule {

  @Override
  public List<TacoViolation> evaluate(Taco taco, List<Ingredient> ingredients) {
    List<TacoViolation> violations = new ArrayList<>();
    if (ingredients == null || ingredients.isEmpty()) {
      return violations;
    }

    boolean hasExtremeHeat = ingredients.stream()
        .anyMatch(ing -> ing.getSpiceLevel() == SpiceLevel.HOT || ing.getSpiceLevel() == SpiceLevel.EXTRA_HOT);

    if (hasExtremeHeat) {
      boolean hasCoolant = ingredients.stream()
          .anyMatch(ing -> ing.getType() == Type.SAUCE || ing.getType() == Type.CHEESE);

      if (!hasCoolant) {
        violations.add(new TacoViolation(
            "EXTREME_HEAT_REQUIRES_COOLANT",
            "Un taco con nivel de picante ALTO o EXTRA ALTO requiere una salsa o queso para balance térmico",
            "ingredients.sauce"
        ));
      }
    }
    return violations;
  }

  @Override
  public String getRuleName() {
    return "SpicyRequiresCoolingRule";
  }
}
