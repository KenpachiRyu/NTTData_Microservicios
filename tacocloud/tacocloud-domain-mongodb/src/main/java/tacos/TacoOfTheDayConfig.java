package tacos;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Date;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document(collection = "taco_of_the_day")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TacoOfTheDayConfig implements Serializable {
  private static final long serialVersionUID = 1L;

  @Id
  private String id; // Format: "override" or "yyyy-MM-dd"
  private String tacoId;
  private LocalDate date;
  private String reason;
  private Date updatedAt = new Date();

  public TacoOfTheDayConfig(String id, String tacoId, LocalDate date, String reason) {
    this.id = id;
    this.tacoId = tacoId;
    this.date = date;
    this.reason = reason;
    this.updatedAt = new Date();
  }
}
