package tacos.web.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.Taco;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TacoTopRankItem {

  private Taco taco;
  private double averageScore;
  private long totalRatings;

  public long getVoteCount() {
    return totalRatings;
  }
}
