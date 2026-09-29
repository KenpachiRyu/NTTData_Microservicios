package tacos.web.api;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.Ingredient.Type;
import tacos.SpiceLevel;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IngredientResponse {

  private String id;
  private String name;
  private Type type;
  private BigDecimal unitPrice = BigDecimal.ZERO;
  private Boolean available = true;

  // TC-17: Metadatos para clientes
  private Set<DietaryTag> dietaryTags = new HashSet<>();
  private Set<Allergen> allergens = new HashSet<>();
  private SpiceLevel spiceLevel = SpiceLevel.NONE;

  public IngredientResponse(String id, String name, Type type) {
    this(id, name, type, BigDecimal.ZERO, true, new HashSet<>(), new HashSet<>(), SpiceLevel.NONE);
  }

  public IngredientResponse(String id, String name, Type type, BigDecimal unitPrice, Boolean available) {
    this(id, name, type, unitPrice, available, new HashSet<>(), new HashSet<>(), SpiceLevel.NONE);
  }
}
