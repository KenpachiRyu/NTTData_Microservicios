package tacos;

import java.io.Serializable;
import java.util.Date;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
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
@Document(collection = "outbox_events")
@CompoundIndex(name = "status_createdAt_idx", def = "{'status': 1, 'createdAt': 1}")
public class OutboxEvent implements Serializable {
  private static final long serialVersionUID = 1L;

  @Id
  @Builder.Default
  private String id = UUID.randomUUID().toString();

  private String eventId;
  private String aggregateType;
  private String aggregateId;
  private String eventType;

  @Builder.Default
  private String eventVersion = "1.0";

  private String payloadJson;

  @Builder.Default
  private OutboxStatus status = OutboxStatus.NEW;

  @Builder.Default
  private int attempts = 0;

  @Builder.Default
  private Date createdAt = new Date();

  private Date lastAttemptAt;
  private String errorMessage;

  private String lockedBy;
  private Date lockedAt;
  private Date leaseExpiresAt;

  @Version
  private Long version;
}
