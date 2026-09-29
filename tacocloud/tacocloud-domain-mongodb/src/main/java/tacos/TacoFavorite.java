package tacos;

import java.io.Serializable;
import java.util.Date;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document(collection = "favorites")
@CompoundIndex(name = "user_taco_fav_unique", def = "{'userId': 1, 'tacoId': 1}", unique = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TacoFavorite implements Serializable {
  private static final long serialVersionUID = 1L;

  @Id
  private String id;
  private String userId;
  private String tacoId;
  private Date createdAt = new Date();

  public TacoFavorite(String userId, String tacoId) {
    this.userId = userId;
    this.tacoId = tacoId;
    this.createdAt = new Date();
  }
}
