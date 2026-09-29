package tacos.physics;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Taco;
import tacos.data.IngredientRepository;

@Service
public class TacoPhysicsValidator {

  private final List<TacoPhysicsRule> rules;
  private final IngredientRepository ingredientRepo;

  @Autowired
  public TacoPhysicsValidator(List<TacoPhysicsRule> rules, IngredientRepository ingredientRepo) {
    this.rules = rules != null ? rules : new ArrayList<>();
    this.ingredientRepo = ingredientRepo;
  }

  public ValidationReport validate(Taco taco, List<Ingredient> ingredients) {
    String tacoName = taco != null ? taco.getName() : "Custom Taco";
    List<TacoViolation> allViolations = new ArrayList<>();

    for (TacoPhysicsRule rule : rules) {
      List<TacoViolation> ruleViolations = rule.evaluate(taco, ingredients);
      if (ruleViolations != null && !ruleViolations.isEmpty()) {
        allViolations.addAll(ruleViolations);
      }
    }

    if (allViolations.isEmpty()) {
      return ValidationReport.ok(tacoName);
    } else {
      return ValidationReport.failed(tacoName, allViolations);
    }
  }

  public Mono<ValidationReport> validateTaco(Taco taco) {
    if (taco == null) {
      return Mono.just(ValidationReport.failed("Null Taco", List.of(new TacoViolation("NULL_TACO", "El taco no puede ser nulo"))));
    }

    if (taco.getIngredients() == null || taco.getIngredients().isEmpty()) {
      return Mono.just(validate(taco, List.of()));
    }

    List<String> ingredientIds = taco.getIngredients().stream()
        .map(Ingredient::getId)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());

    if (ingredientIds.isEmpty()) {
      return Mono.just(validate(taco, List.of()));
    }

    return ingredientRepo.findAllById(ingredientIds)
        .collectList()
        .map(ingredients -> validate(taco, ingredients));
  }
}
