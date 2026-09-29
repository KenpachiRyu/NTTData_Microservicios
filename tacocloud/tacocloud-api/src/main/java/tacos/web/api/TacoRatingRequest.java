package tacos.web.api;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TacoRatingRequest {

  @NotNull(message = "La puntuación es obligatoria")
  @Min(value = 1, message = "La puntuación mínima es 1")
  @Max(value = 5, message = "La puntuación máxima es 5")
  private Integer score;
}
