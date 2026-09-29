package tacos.web.api;

import javax.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenizeRequest {

  @NotBlank(message = "El número de tarjeta es obligatorio")
  private String cardNumber;

  @NotBlank(message = "El CVV es obligatorio")
  private String cvv;

  @NotBlank(message = "La fecha de expiración es obligatoria")
  private String expiration;

}
