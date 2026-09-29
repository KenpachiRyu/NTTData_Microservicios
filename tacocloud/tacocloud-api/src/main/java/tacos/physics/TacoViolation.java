package tacos.physics;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TacoViolation {

  private String code;
  private String message;
  private String field;

  public TacoViolation(String code, String message) {
    this(code, message, "ingredients");
  }
}
