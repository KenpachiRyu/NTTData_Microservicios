package tacos.web.api;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.Ingredient;
import tacos.SpiceLevel;
import tacos.Taco;
import tacos.data.IngredientRepository;

@Service
public class TacoClassificationService {

  private final IngredientRepository ingredientRepo;

  @Autowired
  public TacoClassificationService(IngredientRepository ingredientRepo) {
    this.ingredientRepo = ingredientRepo;
  }

  public TacoClassificationResponse classify(Taco taco, List<Ingredient> ingredients) {
    String tacoId = taco != null ? taco.getId() : null;
    String tacoName = taco != null ? taco.getName() : "Custom Taco";

    if (ingredients == null || ingredients.isEmpty()) {
      return new TacoClassificationResponse(tacoId, tacoName, Collections.emptySet(), Collections.emptySet(), SpiceLevel.NONE);
    }

    // 1. Unión matemática exacta de todos los alérgenos
    Set<Allergen> unionAllergens = new HashSet<>();
    for (Ingredient ing : ingredients) {
      if (ing.getAllergens() != null) {
        unionAllergens.addAll(ing.getAllergens());
      }
    }

    // 2. Reglas dietarias estrictas (solo verdaderas si el 100% de ingredientes cumplen)
    boolean allVegan = ingredients.stream()
        .allMatch(ing -> ing.getDietaryTags() != null && ing.getDietaryTags().contains(DietaryTag.VEGAN));

    boolean allVegetarian = ingredients.stream()
        .allMatch(ing -> ing.getDietaryTags() != null && (
            ing.getDietaryTags().contains(DietaryTag.VEGAN) || ing.getDietaryTags().contains(DietaryTag.VEGETARIAN)
        ));

    boolean glutenFree = !unionAllergens.contains(Allergen.GLUTEN) &&
        ingredients.stream().allMatch(ing -> ing.getDietaryTags() != null && ing.getDietaryTags().contains(DietaryTag.GLUTEN_FREE));

    Set<DietaryTag> derivedTags = new HashSet<>();
    if (allVegan) {
      derivedTags.add(DietaryTag.VEGAN);
    }
    if (allVegetarian) {
      derivedTags.add(DietaryTag.VEGETARIAN);
    }
    if (glutenFree) {
      derivedTags.add(DietaryTag.GLUTEN_FREE);
    }

    // 3. Política de nivel de picante: máximo heatRank entre todos los ingredientes
    SpiceLevel maxSpice = SpiceLevel.NONE;
    for (Ingredient ing : ingredients) {
      SpiceLevel current = ing.getSpiceLevel() != null ? ing.getSpiceLevel() : SpiceLevel.NONE;
      if (current.getHeatRank() > maxSpice.getHeatRank()) {
        maxSpice = current;
      }
    }

    return new TacoClassificationResponse(tacoId, tacoName, derivedTags, unionAllergens, maxSpice);
  }

  public Mono<TacoClassificationResponse> classifyTaco(Taco taco) {
    if (taco == null || taco.getIngredients() == null || taco.getIngredients().isEmpty()) {
      return Mono.just(new TacoClassificationResponse(
          taco != null ? taco.getId() : null,
          taco != null ? taco.getName() : null,
          Collections.emptySet(), Collections.emptySet(), SpiceLevel.NONE));
    }

    List<String> ingredientIds = taco.getIngredients().stream()
        .map(Ingredient::getId)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());

    return ingredientRepo.findAllById(ingredientIds)
        .collectList()
        .map(ingredients -> classify(taco, ingredients));
  }
}
