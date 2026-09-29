package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tacos.DietaryTag;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.SpiceLevel;
import tacos.Taco;
import tacos.physics.AvailableIngredientsRule;
import tacos.physics.IngredientCountRule;
import tacos.physics.NoDuplicateIngredientsRule;
import tacos.physics.SingleBaseRule;
import tacos.physics.SpicyRequiresCoolingRule;
import tacos.physics.TacoPhysicsRule;
import tacos.physics.TacoPhysicsValidator;
import tacos.physics.ValidationReport;
import tacos.physics.VeganNoMeatRule;

public class TacoPhysicsTest {

  private TacoPhysicsValidator validator;

  private Ingredient flto;
  private Ingredient coto;
  private Ingredient grbf;
  private Ingredient ched;
  private Ingredient slsa;
  private Ingredient ghostPepper;
  private Ingredient unavailableIng;

  @BeforeEach
  public void setUp() {
    List<TacoPhysicsRule> rules = Arrays.asList(
        new SingleBaseRule(),
        new IngredientCountRule(),
        new NoDuplicateIngredientsRule(),
        new AvailableIngredientsRule(),
        new SpicyRequiresCoolingRule(),
        new VeganNoMeatRule()
    );
    validator = new TacoPhysicsValidator(rules, null);

    flto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, new BigDecimal("0.50"));
    flto.setAvailable(true);

    coto = new Ingredient("COTO", "Corn Tortilla", Type.WRAP, new BigDecimal("0.60"));
    coto.setAvailable(true);

    grbf = new Ingredient("GRBF", "Ground Beef", Type.PROTEIN, new BigDecimal("1.50"));
    grbf.setAvailable(true);

    ched = new Ingredient("CHED", "Cheddar", Type.CHEESE, new BigDecimal("0.50"));
    ched.setAvailable(true);

    slsa = new Ingredient("SLSA", "Salsa", Type.SAUCE, new BigDecimal("0.40"));
    slsa.setAvailable(true);
    slsa.setSpiceLevel(SpiceLevel.MEDIUM);

    ghostPepper = new Ingredient("GHST", "Ghost Pepper Sauce", Type.SAUCE, new BigDecimal("0.75"));
    ghostPepper.setAvailable(true);
    ghostPepper.setSpiceLevel(SpiceLevel.EXTRA_HOT);

    unavailableIng = new Ingredient("AVOC", "Avocado", Type.VEGGIES, new BigDecimal("1.00"));
    unavailableIng.setAvailable(false);
  }

  @Test
  public void shouldPassValidTacoDesignWithoutViolations() {
    Taco taco = new Taco();
    taco.setName("Classic Beef");
    List<Ingredient> ingredients = Arrays.asList(flto, grbf, ched, slsa);

    ValidationReport report = validator.validate(taco, ingredients);

    assertTrue(report.isValid());
    assertEquals(0, report.getViolations().size());
  }

  @Test
  public void shouldFailWhenNoBaseOrMultipleBasesAreProvided() {
    Taco noBase = new Taco();
    noBase.setName("No Base");
    ValidationReport report1 = validator.validate(noBase, Arrays.asList(grbf, ched));
    assertFalse(report1.isValid());
    assertTrue(report1.getViolations().stream().anyMatch(v -> "INVALID_BASE_COUNT".equals(v.getCode())));

    Taco doubleBase = new Taco();
    doubleBase.setName("Double Base");
    ValidationReport report2 = validator.validate(doubleBase, Arrays.asList(flto, coto, grbf));
    assertFalse(report2.isValid());
    assertTrue(report2.getViolations().stream().anyMatch(v -> "INVALID_BASE_COUNT".equals(v.getCode())));
  }

  @Test
  public void shouldFailWhenIngredientCountIsOutsideAllowedRange() {
    Taco singleIng = new Taco();
    singleIng.setName("Just Wrap");
    ValidationReport report1 = validator.validate(singleIng, Collections.singletonList(flto));
    assertFalse(report1.isValid());
    assertTrue(report1.getViolations().stream().anyMatch(v -> "INVALID_INGREDIENT_COUNT".equals(v.getCode())));

    Taco tooMany = new Taco();
    tooMany.setName("Mountain");
    List<Ingredient> many = new ArrayList<>();
    many.add(flto);
    for (int i = 0; i < 13; i++) {
      Ingredient dummy = new Ingredient("DUM" + i, "Dummy " + i, Type.VEGGIES);
      dummy.setAvailable(true);
      many.add(dummy);
    }
    ValidationReport report2 = validator.validate(tooMany, many);
    assertFalse(report2.isValid());
    assertTrue(report2.getViolations().stream().anyMatch(v -> "INVALID_INGREDIENT_COUNT".equals(v.getCode())));
  }

  @Test
  public void shouldFailWhenDuplicateIngredientsArePresent() {
    Taco dupTaco = new Taco();
    dupTaco.setName("Double Beef");
    ValidationReport report = validator.validate(dupTaco, Arrays.asList(flto, grbf, grbf));

    assertFalse(report.isValid());
    assertTrue(report.getViolations().stream().anyMatch(v -> "DUPLICATE_INGREDIENTS".equals(v.getCode())));
  }

  @Test
  public void shouldFailWhenIngredientIsUnavailable() {
    Taco taco = new Taco();
    taco.setName("Avocado Taco");
    ValidationReport report = validator.validate(taco, Arrays.asList(flto, unavailableIng));

    assertFalse(report.isValid());
    assertTrue(report.getViolations().stream().anyMatch(v -> "UNAVAILABLE_INGREDIENT".equals(v.getCode())));
  }

  @Test
  public void shouldFailWhenExtremeHeatLacksCoolingElement() {
    // Ingrediente picante tipo PROTEIN para simular ausencia de salsa o queso
    Ingredient spicyMeat = new Ingredient("SPBF", "Spicy Beef", Type.PROTEIN);
    spicyMeat.setAvailable(true);
    spicyMeat.setSpiceLevel(SpiceLevel.HOT);

    Taco hotTaco = new Taco();
    hotTaco.setName("Blazing Beef");
    ValidationReport report = validator.validate(hotTaco, Arrays.asList(flto, spicyMeat));

    assertFalse(report.isValid());
    assertTrue(report.getViolations().stream().anyMatch(v -> "EXTREME_HEAT_REQUIRES_COOLANT".equals(v.getCode())));
  }

  @Test
  public void shouldFailWhenVeganTacoContainsAnimalProtein() {
    Taco veganTaco = new Taco();
    veganTaco.setName("Vegan Carnivore Special");
    ValidationReport report = validator.validate(veganTaco, Arrays.asList(flto, grbf, slsa));

    assertFalse(report.isValid());
    assertTrue(report.getViolations().stream().anyMatch(v -> "VEGAN_MEAT_CONFLICT".equals(v.getCode())));
  }

  @Test
  public void shouldCollectMultipleViolationsSimultaneously() {
    Taco disasterTaco = new Taco();
    disasterTaco.setName("Vegan Disaster");
    // Violaciones esperadas:
    // 1. INVALID_BASE_COUNT (0 bases)
    // 2. DUPLICATE_INGREDIENTS (grbf duplicado)
    // 3. UNAVAILABLE_INGREDIENT (unavailableIng)
    // 4. VEGAN_MEAT_CONFLICT (vegan con carne)
    ValidationReport report = validator.validate(disasterTaco, Arrays.asList(grbf, grbf, unavailableIng));

    assertFalse(report.isValid());
    assertTrue(report.getViolations().size() >= 3);
  }
}
