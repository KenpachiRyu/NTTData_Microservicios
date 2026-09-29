package tacos;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@Document
public class Ingredient {

  @Id
  private String id;
  private String name;
  private Type type;

  // TC-13: Campo de precio unitario usando BigDecimal
  private BigDecimal unitPrice = BigDecimal.ZERO;

  // TC-13: Disponibilidad y stock para venta
  private Boolean available = true;

  @com.fasterxml.jackson.annotation.JsonIgnore
  private Integer stockOnHand = 0;

  @com.fasterxml.jackson.annotation.JsonIgnore
  private Integer reorderLevel = 0;

  // TC-13: Optimistic locking para concurrencia
  @Version
  @com.fasterxml.jackson.annotation.JsonIgnore
  private Long version;

  // TC-17: Clasificación dietaria, alérgenos y picante
  private Set<DietaryTag> dietaryTags = new HashSet<>();
  private Set<Allergen> allergens = new HashSet<>();
  private SpiceLevel spiceLevel = SpiceLevel.NONE;

  public Ingredient(String id, String name, Type type) {
    this(id, name, type, BigDecimal.ZERO);
  }

  public Ingredient(String id, String name, Type type, BigDecimal unitPrice) {
    this.id = id;
    this.name = name;
    this.type = type;
    this.unitPrice = unitPrice != null ? unitPrice : BigDecimal.ZERO;
    this.available = true;
    this.stockOnHand = 0;
    this.reorderLevel = 0;
    this.dietaryTags = new HashSet<>();
    this.allergens = new HashSet<>();
    this.spiceLevel = SpiceLevel.NONE;
  }

  public enum Type {
    WRAP, PROTEIN, VEGGIES, CHEESE, SAUCE
  }

}