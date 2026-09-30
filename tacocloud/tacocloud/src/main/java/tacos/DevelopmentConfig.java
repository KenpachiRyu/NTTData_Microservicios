package tacos;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import reactor.core.publisher.Mono;
import tacos.Ingredient.Type;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;

@Profile("!prod")
@Configuration
public class DevelopmentConfig {

  @Bean
  public CommandLineRunner dataLoader(IngredientRepository repo,
        UserRepository userRepo, PasswordEncoder encoder, TacoRepository tacoRepo,
        PaymentMethodRepository paymentMethodRepo, OrderRepository orderRepo) {
    
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
        Ingredient jalapeño = saveIngredient("JALA", "Jalapeño Slices", Type.VEGGIES, "0.75", 100, 10,
            setOf(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE), setOf(), SpiceLevel.HOT);
        
        User habuma = saveOrUpdateUser(userRepo, "habuma", encoder.encode("password"), 
              "Craig Walls", "123 North Street", "Cross Roads", "TX", 
              "76227", "123-123-1234", "craig@habuma.com", Arrays.asList("ROLE_USER"));
        paymentMethodRepo.save(new PaymentMethod(habuma, "4111111111111111", "321", "10/25")).block();

        User craig = saveOrUpdateUser(userRepo, "craig", encoder.encode("password"), 
              "Craig Walls", "123 North Street", "Cross Roads", "TX", 
              "76227", "123-123-1234", "craig@tacocloud.com", Arrays.asList("ROLE_USER"));

        User admin = saveOrUpdateUser(userRepo, "admin", encoder.encode("password"), 
              "System Admin", "100 Admin Ave", "Austin", "TX", 
              "78701", "555-555-5555", "admin@tacocloud.com", Arrays.asList("ROLE_ADMIN", "ROLE_USER", "ROLE_KITCHEN"));

        Taco taco1 = new Taco();
        taco1.setId("TACO1");
        taco1.setName("Carnivore");
        taco1.setIngredients(Arrays.asList(flourTortilla, groundBeef, carnitas, sourCream, salsa, cheddar));
        tacoRepo.save(taco1).block();

        Taco taco2 = new Taco();
        taco2.setId("TACO2");
        taco2.setName("Bovine Bounty");
        taco2.setIngredients(Arrays.asList(cornTortilla, groundBeef, cheddar, jack, sourCream));
        tacoRepo.save(taco2).block();

        Taco taco3 = new Taco();
        taco3.setId("TACO3");
        taco3.setName("Veg-Out");
        taco3.setIngredients(Arrays.asList(flourTortilla, tomatoes, lettuce, salsa));
        tacoRepo.save(taco3).block();

        // Precarga de órdenes demo en estado CREATED para TC-04 (PATCH), TC-25/26 (Cola/Claim)
        saveOrder(orderRepo, "ORDER-DEMO-001", craig, taco1);
        saveOrder(orderRepo, "order-100", craig, taco1);
      }

      private User saveOrUpdateUser(UserRepository userRepo, String username, String encodedPassword,
          String fullname, String street, String city, String state, String zip, String phone, String email,
          List<String> roles) {
        return userRepo.findByUsername(username)
            .flatMap(existing -> {
              User updated = new User(username, encodedPassword, fullname, street, city, state, zip, phone, email);
              updated.setId(existing.getId());
              updated.setRoles(new ArrayList<>(roles));
              return userRepo.save(updated);
            })
            .switchIfEmpty(Mono.defer(() -> {
              User newUser = new User(username, encodedPassword, fullname, street, city, state, zip, phone, email);
              newUser.setRoles(new ArrayList<>(roles));
              return userRepo.save(newUser);
            }))
            .block();
      }

      private void saveOrder(OrderRepository orderRepo, String id, User user, Taco taco) {
        orderRepo.findById(id)
            .flatMap(existing -> {
              existing.setStatus(OrderStatus.CREATED);
              existing.setDeliveryZip("78701");
              existing.setDeliveryState("TX");
              existing.setStationId(null);
              existing.setCookId(null);
              return orderRepo.save(existing);
            })
            .switchIfEmpty(Mono.defer(() -> {
              TacoOrder order = new TacoOrder();
              order.setId(id);
              order.setStatus(OrderStatus.CREATED);
              order.setDeliveryName("Craig Walls");
              order.setDeliveryStreet("123 Taco Way");
              order.setDeliveryCity("Austin");
              order.setDeliveryState("TX");
              order.setDeliveryZip("78701");
              order.setUser(user);
              order.setPaymentToken("tok_visa_4242");
              order.setTotal(new BigDecimal("12.20"));
              order.setSubtotal(new BigDecimal("12.20"));
              order.setPlacedAt(new Date());
              order.setTacos(Arrays.asList(taco));
              return orderRepo.save(order);
            }))
            .block();
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
        repo.save(ingredient).block();
        return ingredient;
      }
    };
  }
}
