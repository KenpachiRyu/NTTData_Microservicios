package tacos.web.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TacoRatingSummary {

  private String tacoId;
  private double averageScore;
  private long totalRatings;
  private Integer userRating;

  public long getTotalVotes() {
    return totalRatings;
  }

  public Integer getUserScore() {
    return userRating;
  }
}
