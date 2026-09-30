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
@Document(collection = "ops_announcements")
public class OpsAnnouncement implements Serializable {
  private static final long serialVersionUID = 1L;

  public enum Severity {
    INFO,
    WARN,
    CRITICAL
  }

  @Id
  @Builder.Default
  private String id = UUID.randomUUID().toString();

  private String text;

  @Builder.Default
  private Severity severity = Severity.INFO;

  @Builder.Default
  private Date createdAt = new Date();

  private Date expiresAt;

  private String createdBy;

  @Builder.Default
  private boolean active = true;
}
