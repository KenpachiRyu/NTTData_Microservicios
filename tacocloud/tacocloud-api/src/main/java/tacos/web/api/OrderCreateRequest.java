package tacos.web.api;

import java.util.ArrayList;
import java.util.List;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import lombok.Data;
import tacos.Taco;

@Data
public class OrderCreateRequest {

  private String id;

  @NotBlank(message = "El nombre de entrega es obligatorio")
  private String deliveryName;

  @NotBlank(message = "La calle de entrega es obligatoria")
  private String deliveryStreet;

  @NotBlank(message = "La ciudad de entrega es obligatoria")
  private String deliveryCity;

  @NotBlank(message = "El estado de entrega es obligatorio")
  private String deliveryState;

  @NotBlank(message = "El código postal de entrega es obligatorio")
  private String deliveryZip;

  @NotBlank(message = "El token de pago es obligatorio")
  private String paymentToken;

  @NotEmpty(message = "La orden debe contener al menos un taco")
  private List<Taco> tacos = new ArrayList<>();
}