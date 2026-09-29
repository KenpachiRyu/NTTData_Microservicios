package tacos.physics;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tacos.DietaryTag;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.Taco;

@Component
public class VeganNoMeatRule implements TacoPhysicsRule {

  @Override
  public List<TacoViolation> evaluate(Taco taco, List<Ingredient> ingredients) {
    List<TacoViolation> violations = new ArrayList<>();
    if (ingredients == null || ingredients.isEmpty()) {
      return violations;
    }

    boolean isExplicitlyVegan = taco.getName() != null && taco.getName().toLowerCase().contains("vegan");
    boolean hasMeat = ingredients.stream()
        .anyMatch(ing -> ing.getType() == Type.PROTEIN &&
            (ing.getDietaryTags() == null || !ing.getDietaryTags().contains(DietaryTag.VEGAN)));

    if (isExplicitlyVegan && hasMeat) {
      violations.add(new TacoViolation(
          "VEGAN_MEAT_CONFLICT",
          "Un taco autodefinido como 'Vegan' no puede contener ingredientes de proteína animal",
          "name"
      ));
    }
    return violations;
  }

  @Override
  public String getRuleName() {
    return "VeganNoMeatRule";
  }
}
