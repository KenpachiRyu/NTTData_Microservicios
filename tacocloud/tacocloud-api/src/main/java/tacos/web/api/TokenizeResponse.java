package tacos.web.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenizeResponse {

  private String paymentToken;
  private String brand;
  private String last4;
  private String expiration;

}
