package tacos;

import java.io.Serializable;
import java.util.Date;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatusChange implements Serializable {
  private static final long serialVersionUID = 1L;

  private OrderStatus fromStatus;
  private OrderStatus toStatus;
  private Date timestamp = new Date();
  private String updatedBy;
  private String source;
  private String reason;
}
