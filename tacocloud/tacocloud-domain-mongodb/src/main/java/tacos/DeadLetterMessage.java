package tacos;

import java.io.Serializable;
import java.util.Date;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "dead_letter_messages")
public class DeadLetterMessage implements Serializable {
  private static final long serialVersionUID = 1L;

  @Id
  @Builder.Default
  private String id = UUID.randomUUID().toString();

  private String eventId;
  private String correlationId;
  private String eventType;
  private String eventVersion;
  private String payloadJson;
  private int attempts;
  private String errorCause;
  private String exceptionClass;

  @Builder.Default
  private Date failedAt = new Date();

  @Builder.Default
  private boolean reprocessed = false;

  private Date reprocessedAt;
}
