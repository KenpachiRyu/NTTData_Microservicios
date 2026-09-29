package tacos.physics;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ValidationReport {

  private boolean valid;
  private String tacoName;
  private List<TacoViolation> violations = new ArrayList<>();

  public static ValidationReport ok(String tacoName) {
    return new ValidationReport(true, tacoName, new ArrayList<>());
  }

  public static ValidationReport failed(String tacoName, List<TacoViolation> violations) {
    return new ValidationReport(false, tacoName, violations);
  }
}
