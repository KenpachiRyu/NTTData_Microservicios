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
@Document(collection = "idempotency_records")
@CompoundIndex(name = "idempotency_key_user_idx", def = "{'key': 1, 'userId': 1}", unique = true)
public class IdempotencyRecord implements Serializable {
  private static final long serialVersionUID = 1L;

  public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
  public static final String STATUS_COMPLETED = "COMPLETED";
  public static final String STATUS_FAILED = "FAILED";

  @Id
  @Builder.Default
  private String id = UUID.randomUUID().toString();

  private String key;
  private String userId;
  private String requestHash;
  private String status;
  private Integer statusCode;
  private String responseBody;

  @Builder.Default
  private Date createdAt = new Date();

  private Date completedAt;
  private Date expiresAt;
}
