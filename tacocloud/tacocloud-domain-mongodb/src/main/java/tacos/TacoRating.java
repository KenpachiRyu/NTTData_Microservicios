package tacos;

import java.io.Serializable;
import java.util.Date;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document(collection = "ratings")
@CompoundIndex(name = "user_taco_rating_unique", def = "{'userId': 1, 'tacoId': 1}", unique = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TacoRating implements Serializable {
  private static final long serialVersionUID = 1L;

  @Id
  private String id;
  private String userId;
  private String tacoId;
  private int score; // 1 to 5
  private Date createdAt = new Date();
  private Date updatedAt = new Date();

  public TacoRating(String userId, String tacoId, int score) {
    this.userId = userId;
    this.tacoId = tacoId;
    this.score = score;
    this.createdAt = new Date();
    this.updatedAt = new Date();
  }
}
