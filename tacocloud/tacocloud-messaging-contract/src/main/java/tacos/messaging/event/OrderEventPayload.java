package tacos.messaging.event;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderEventPayload implements Serializable {
  private static final long serialVersionUID = 1L;

  private String orderId;
  private Date placedAt;
  private String status;
  private BigDecimal total;
  private String deliveryCity;
  private String customerName;

  @Builder.Default
  private List<OrderEventTaco> tacos = new ArrayList<>();
}
