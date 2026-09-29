package tacos.messaging.event;

import java.io.Serializable;
import java.util.Date;
import java.util.UUID;

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
public class OrderEvent implements Serializable {
  private static final long serialVersionUID = 1L;

  @Builder.Default
  private String eventId = UUID.randomUUID().toString();

  private OrderEventType eventType;

  @Builder.Default
  private String version = "1.0";

  @Builder.Default
  private Date occurredAt = new Date();

  @Builder.Default
  private String correlationId = UUID.randomUUID().toString();

  private OrderEventPayload payload;
}
