package tacos.web.api;

import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.Taco;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TacoOfTheDayResponse {

  private Taco taco;
  private LocalDate date;
  private String reason;
}
