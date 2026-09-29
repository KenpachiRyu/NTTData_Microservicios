package tacos.web.api;

import java.math.BigDecimal;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.SpiceLevel;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminIngredientResponse {

  private String id;
  private String name;
  private Type type;
  private BigDecimal unitPrice;
  private Boolean available;
  private Integer stockOnHand;
  private Integer reorderLevel;
  private Long version;
  private Set<DietaryTag> dietaryTags;
  private Set<Allergen> allergens;
  private SpiceLevel spiceLevel;

  public static AdminIngredientResponse fromDomain(Ingredient ing) {
    if (ing == null) {
      return null;
    }
    return new AdminIngredientResponse(
        ing.getId(),
        ing.getName(),
        ing.getType(),
        ing.getUnitPrice(),
        ing.getAvailable(),
        ing.getStockOnHand(),
        ing.getReorderLevel(),
        ing.getVersion(),
        ing.getDietaryTags(),
        ing.getAllergens(),
        ing.getSpiceLevel()
    );
  }
}
