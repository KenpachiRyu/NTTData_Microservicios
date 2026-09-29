package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tacos.Allergen;
import tacos.DietaryTag;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.SpiceLevel;
import tacos.Taco;

public class TacoClassificationTest {

  private TacoClassificationService classificationService;

  private Ingredient cornTortilla;
  private Ingredient flourTortilla;
  private Ingredient groundBeef;
  private Ingredient cheddar;
  private Ingredient salsa;
  private Ingredient ghostPepperSauce;

  @BeforeEach
  public void setUp() {
    classificationService = new TacoClassificationService(null);

    cornTortilla = new Ingredient("COTO", "Corn Tortilla", Type.WRAP, new BigDecimal("0.60"));
    cornTortilla.setDietaryTags(new HashSet<>(Arrays.asList(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE)));
    cornTortilla.setAllergens(Collections.emptySet());
    cornTortilla.setSpiceLevel(SpiceLevel.NONE);

    flourTortilla = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, new BigDecimal("0.50"));
    flourTortilla.setDietaryTags(new HashSet<>(Arrays.asList(DietaryTag.VEGAN, DietaryTag.VEGETARIAN)));
    flourTortilla.setAllergens(new HashSet<>(Collections.singletonList(Allergen.GLUTEN)));
    flourTortilla.setSpiceLevel(SpiceLevel.NONE);

    groundBeef = new Ingredient("GRBF", "Ground Beef", Type.PROTEIN, new BigDecimal("1.50"));
    groundBeef.setDietaryTags(Collections.emptySet()); // Carne animal
    groundBeef.setAllergens(Collections.emptySet());
    groundBeef.setSpiceLevel(SpiceLevel.NONE);

    cheddar = new Ingredient("CHED", "Cheddar", Type.CHEESE, new BigDecimal("0.50"));
    cheddar.setDietaryTags(new HashSet<>(Arrays.asList(DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE)));
    cheddar.setAllergens(new HashSet<>(Collections.singletonList(Allergen.DAIRY)));
    cheddar.setSpiceLevel(SpiceLevel.NONE);

    salsa = new Ingredient("SLSA", "Salsa", Type.SAUCE, new BigDecimal("0.40"));
    salsa.setDietaryTags(new HashSet<>(Arrays.asList(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE)));
    salsa.setAllergens(Collections.emptySet());
    salsa.setSpiceLevel(SpiceLevel.MEDIUM);

    ghostPepperSauce = new Ingredient("GHST", "Ghost Pepper", Type.SAUCE, new BigDecimal("0.75"));
    ghostPepperSauce.setDietaryTags(new HashSet<>(Arrays.asList(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE)));
    ghostPepperSauce.setAllergens(Collections.emptySet());
    ghostPepperSauce.setSpiceLevel(SpiceLevel.EXTRA_HOT);
  }

  @Test
  public void shouldClassifyAsVeganOnlyWhenAllIngredientsAreVegan() {
    Taco veganTaco = new Taco();
    veganTaco.setName("Vegan Delight");
    veganTaco.setIngredients(Arrays.asList(cornTortilla, salsa));

    TacoClassificationResponse res = classificationService.classify(veganTaco, Arrays.asList(cornTortilla, salsa));
    assertTrue(res.getDietaryTags().contains(DietaryTag.VEGAN));
    assertTrue(res.getDietaryTags().contains(DietaryTag.VEGETARIAN));
    assertTrue(res.getDietaryTags().contains(DietaryTag.GLUTEN_FREE));

    // Si agregamos queso cheddar (lácteo), deja de ser vegano pero sigue siendo vegetariano
    Taco nonVegan = new Taco();
    nonVegan.setName("Cheesy Veggie");
    nonVegan.setIngredients(Arrays.asList(cornTortilla, cheddar, salsa));

    TacoClassificationResponse res2 = classificationService.classify(nonVegan, Arrays.asList(cornTortilla, cheddar, salsa));
    assertFalse(res2.getDietaryTags().contains(DietaryTag.VEGAN));
    assertTrue(res2.getDietaryTags().contains(DietaryTag.VEGETARIAN));
  }

  @Test
  public void shouldNotClassifyAsVegetarianWhenMeatIsPresent() {
    Taco meatTaco = new Taco();
    meatTaco.setName("Beef Taco");
    meatTaco.setIngredients(Arrays.asList(cornTortilla, groundBeef, salsa));

    TacoClassificationResponse res = classificationService.classify(meatTaco, Arrays.asList(cornTortilla, groundBeef, salsa));
    assertFalse(res.getDietaryTags().contains(DietaryTag.VEGAN));
    assertFalse(res.getDietaryTags().contains(DietaryTag.VEGETARIAN));
  }

  @Test
  public void shouldPerformMathematicalUnionOfAllergensWithoutMajorityVote() {
    Taco loadedTaco = new Taco();
    loadedTaco.setName("Loaded Taco");
    // Flour Tortilla (GLUTEN) + Cheddar (DAIRY) + Salsa (ninguno)
    loadedTaco.setIngredients(Arrays.asList(flourTortilla, cheddar, salsa));

    TacoClassificationResponse res = classificationService.classify(loadedTaco, Arrays.asList(flourTortilla, cheddar, salsa));

    Set<Allergen> allergens = res.getAllergens();
    assertEquals(2, allergens.size());
    assertTrue(allergens.contains(Allergen.GLUTEN));
    assertTrue(allergens.contains(Allergen.DAIRY));
    assertFalse(res.getDietaryTags().contains(DietaryTag.GLUTEN_FREE));
  }

  @Test
  public void shouldDetermineSpiceLevelDeterministicallyAsMaximumHeat() {
    Taco spicyTaco = new Taco();
    spicyTaco.setName("Inferno");
    spicyTaco.setIngredients(Arrays.asList(cornTortilla, groundBeef, salsa, ghostPepperSauce));

    TacoClassificationResponse res = classificationService.classify(
        spicyTaco, Arrays.asList(cornTortilla, groundBeef, salsa, ghostPepperSauce));

    // Salsa es MEDIUM (2), Ghost Pepper es EXTRA_HOT (4) -> Result debe ser EXTRA_HOT (4)
    assertEquals(SpiceLevel.EXTRA_HOT, res.getSpiceLevel());
  }

  @Test
  public void shouldIncludeAcademicDisclaimerAgainstCrossContamination() {
    Taco taco = new Taco();
    taco.setName("Simple Taco");
    TacoClassificationResponse res = classificationService.classify(taco, Collections.singletonList(cornTortilla));

    assertTrue(res.getDisclaimer().contains("AVISO ACADÉMICO"));
    assertTrue(res.getDisclaimer().contains("contaminación cruzada"));
  }
}
