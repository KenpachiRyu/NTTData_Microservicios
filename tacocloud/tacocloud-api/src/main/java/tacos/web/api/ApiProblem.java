package tacos.web.api;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ApiProblem {

  private URI type;
  private String title;
  private int status;
  private String detail;
  private String instance;
  private String code;
  private String correlationId;

  @Builder.Default
  private List<Violation> violations = new ArrayList<>();

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Violation {
    private String field;
    private String message;
  }
}
