package tacos;

import java.io.Serializable;
import java.util.Date;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "processed_events")
@CompoundIndex(name = "eventId_consumer_unique_idx", def = "{'eventId': 1, 'consumerName': 1}", unique = true)
public class ProcessedEvent implements Serializable {
  private static final long serialVersionUID = 1L;

  @Id
  @Builder.Default
  private String id = UUID.randomUUID().toString();

  private String eventId;
  private String consumerName;
  private String eventType;

  @Builder.Default
  private Date processedAt = new Date();

  private String result;
  private Date leaseExpiresAt;
  private String errorMessage;
}
