package tacos.web.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import javax.validation.constraints.NotBlank;
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

  // Soporte para tacos directos (compatibilidad) y líneas de pedido con cantidad (TC-14)
  private List<Taco> tacos = new ArrayList<>();
  private List<OrderItemRequest> items = new ArrayList<>();

  // Cupón opcional (TC-15)
  private String couponCode;

  // Campo enviado por el cliente que será estrictamente ignorado por el servidor (TC-14)
  private BigDecimal clientCalculatedTotal;

  public boolean hasItemsOrTacos() {
    return (items != null && !items.isEmpty()) || (tacos != null && !tacos.isEmpty());
  }

  // Métodos de compatibilidad segura: tokenizan ccNumber y descartan CVV (TC-12)
  public String getCcNumber() {
    return paymentToken;
  }

  public void setCcNumber(String ccNumber) {
    if ((this.paymentToken == null || this.paymentToken.isEmpty()) && ccNumber != null && !ccNumber.isEmpty()) {
      this.paymentToken = ccNumber.startsWith("tok_") ? ccNumber : "tok_" + Math.abs(ccNumber.hashCode());
    }
  }

  public String getCcCVV() {
    return null;
  }

  public void setCcCVV(String ccCVV) {
    // Descartado estrictamente para seguridad PCI-DSS (TC-12)
  }

  public String getCcExpiration() {
    return null;
  }

  public void setCcExpiration(String ccExpiration) {
    // Opcional en el request, token de pago representa el método
  }
}