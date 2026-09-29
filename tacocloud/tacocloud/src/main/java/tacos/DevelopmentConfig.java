package tacos;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import tacos.Ingredient.Type;
import tacos.data.IngredientRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;

@Profile("!prod")
@Configuration
public class DevelopmentConfig {

  @Bean
  public CommandLineRunner dataLoader(IngredientRepository repo,
        UserRepository userRepo, PasswordEncoder encoder, TacoRepository tacoRepo,
        PaymentMethodRepository paymentMethodRepo) {
    
    return new CommandLineRunner() {
      @Override
      public void run(String... args) throws Exception {
        Ingredient flourTortilla = saveIngredient("FLTO", "Flour Tortilla", Type.WRAP, "0.50", 100, 10,
            setOf(DietaryTag.VEGAN, DietaryTag.VEGETARIAN), setOf(Allergen.GLUTEN), SpiceLevel.NONE);
        Ingredient cornTortilla = saveIngredient("COTO", "Corn Tortilla", Type.WRAP, "0.60", 100, 10,
            setOf(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE), setOf(), SpiceLevel.NONE);
        Ingredient groundBeef = saveIngredient("GRBF", "Ground Beef", Type.PROTEIN, "1.50", 100, 10,
            setOf(), setOf(), SpiceLevel.NONE);
        Ingredient carnitas = saveIngredient("CARN", "Carnitas", Type.PROTEIN, "1.75", 100, 10,
            setOf(), setOf(), SpiceLevel.NONE);
        Ingredient tomatoes = saveIngredient("TMTO", "Diced Tomatoes", Type.VEGGIES, "0.30", 100, 10,
            setOf(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE), setOf(), SpiceLevel.NONE);
        Ingredient lettuce = saveIngredient("LETC", "Lettuce", Type.VEGGIES, "0.25", 100, 10,
            setOf(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE), setOf(), SpiceLevel.NONE);
        Ingredient cheddar = saveIngredient("CHED", "Cheddar", Type.CHEESE, "0.50", 100, 10,
            setOf(DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE), setOf(Allergen.DAIRY), SpiceLevel.NONE);
        Ingredient jack = saveIngredient("JACK", "Monterrey Jack", Type.CHEESE, "0.50", 100, 10,
            setOf(DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE), setOf(Allergen.DAIRY), SpiceLevel.NONE);
        Ingredient salsa = saveIngredient("SLSA", "Salsa", Type.SAUCE, "0.40", 100, 10,
            setOf(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE), setOf(), SpiceLevel.MEDIUM);
        Ingredient sourCream = saveIngredient("SRCR", "Sour Cream", Type.SAUCE, "0.35", 100, 10,
            setOf(DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE), setOf(Allergen.DAIRY), SpiceLevel.NONE);
        
        userRepo.save(new User("habuma", encoder.encode("password"), 
              "Craig Walls", "123 North Street", "Cross Roads", "TX", 
              "76227", "123-123-1234", "craig@habuma.com"))
          .subscribe(user -> {
              paymentMethodRepo.save(new PaymentMethod(user, "4111111111111111", "321", "10/25")).subscribe();
          });        
        
        Taco taco1 = new Taco();
        taco1.setId("TACO1");
        taco1.setName("Carnivore");
        taco1.setIngredients(Arrays.asList(flourTortilla, groundBeef, carnitas, sourCream, salsa, cheddar));
        tacoRepo.save(taco1).subscribe();

        Taco taco2 = new Taco();
        taco2.setId("TACO2");
        taco2.setName("Bovine Bounty");
        taco2.setIngredients(Arrays.asList(cornTortilla, groundBeef, cheddar, jack, sourCream));
        tacoRepo.save(taco2).subscribe();

        Taco taco3 = new Taco();
        taco3.setId("TACO3");
        taco3.setName("Veg-Out");
        taco3.setIngredients(Arrays.asList(flourTortilla, tomatoes, lettuce, salsa));
        tacoRepo.save(taco3).subscribe();
      }

      @SafeVarargs
      private final <T> Set<T> setOf(T... elements) {
        return new HashSet<>(Arrays.asList(elements));
      }

      private Ingredient saveIngredient(String id, String name, Type type, String price, int stock, int reorder,
                                       Set<DietaryTag> tags, Set<Allergen> allergens, SpiceLevel spice) {
        Ingredient ingredient = new Ingredient(id, name, type, new BigDecimal(price));
        ingredient.setAvailable(true);
        ingredient.setStockOnHand(stock);
        ingredient.setReorderLevel(reorder);
        ingredient.setDietaryTags(tags);
        ingredient.setAllergens(allergens);
        ingredient.setSpiceLevel(spice);
        repo.save(ingredient).subscribe();
        return ingredient;
      }
    };
  }
}
