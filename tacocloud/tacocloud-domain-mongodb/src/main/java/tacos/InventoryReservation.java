package tacos;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryReservation implements Serializable {
  private static final long serialVersionUID = 1L;

  @Id
  private String id;
  private String orderId;
  private String idempotencyKey;
  private Map<String, Integer> reservedQuantities = new HashMap<>(); // ingredientId -> quantity
  private ReservationStatus status = ReservationStatus.PENDING;
  private Date createdAt = new Date();

  public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    RELEASED
  }
}
