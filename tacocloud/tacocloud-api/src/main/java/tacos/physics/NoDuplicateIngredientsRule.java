package tacos.physics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tacos.Ingredient;
import tacos.Taco;

@Component
public class NoDuplicateIngredientsRule implements TacoPhysicsRule {

  @Override
  public List<TacoViolation> evaluate(Taco taco, List<Ingredient> ingredients) {
    List<TacoViolation> violations = new ArrayList<>();
    if (ingredients == null || ingredients.isEmpty()) {
      return violations;
    }

    Set<String> seenIds = new HashSet<>();
    Set<String> duplicates = new HashSet<>();

    for (Ingredient ing : ingredients) {
      if (ing.getId() != null) {
        if (!seenIds.add(ing.getId())) {
          duplicates.add(ing.getId());
        }
      }
    }

    if (!duplicates.isEmpty()) {
      violations.add(new TacoViolation(
          "DUPLICATE_INGREDIENTS",
          "No se permiten ingredientes duplicados en el diseño: " + duplicates,
          "ingredients"
      ));
    }
    return violations;
  }

  @Override
  public String getRuleName() {
    return "NoDuplicateIngredientsRule";
  }
}
