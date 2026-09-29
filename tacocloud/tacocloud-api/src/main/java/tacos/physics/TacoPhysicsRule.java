package tacos.physics;

import java.util.List;
import tacos.Ingredient;
import tacos.Taco;

public interface TacoPhysicsRule {

  List<TacoViolation> evaluate(Taco taco, List<Ingredient> ingredients);

  String getRuleName();
}
