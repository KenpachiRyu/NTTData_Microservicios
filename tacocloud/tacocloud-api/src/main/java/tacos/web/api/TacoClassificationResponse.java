package tacos.web.api;

import java.util.HashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.SpiceLevel;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TacoClassificationResponse {

  public static final String DISCLAIMER =
      "AVISO ACADÉMICO: La clasificación dietaria y de alérgenos se deriva teóricamente de los ingredientes " +
      "y no garantiza la ausencia de trazas o contaminación cruzada en la línea de preparación.";

  private String tacoId;
  private String tacoName;
  private Set<DietaryTag> dietaryTags = new HashSet<>();
  private Set<Allergen> allergens = new HashSet<>();
  private SpiceLevel spiceLevel = SpiceLevel.NONE;
  private String disclaimer = DISCLAIMER;

  public TacoClassificationResponse(String tacoId, String tacoName, Set<DietaryTag> dietaryTags, Set<Allergen> allergens, SpiceLevel spiceLevel) {
    this(tacoId, tacoName, dietaryTags, allergens, spiceLevel, DISCLAIMER);
  }
}
