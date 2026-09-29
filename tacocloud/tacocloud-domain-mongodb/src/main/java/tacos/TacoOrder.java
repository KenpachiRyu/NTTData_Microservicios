package tacos;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;
import lombok.ToString;

@Data
@Document
public class TacoOrder implements Serializable {
  private static final long serialVersionUID = 1L;

  @Id
  private String id;
  private Date placedAt = new Date();

  private User user;

  private String deliveryName;
  private String deliveryStreet;
  private String deliveryCity;
  private String deliveryState;
  private String deliveryZip;

  // TC-12: Tokenización y eliminación de PAN/CVV del dominio
  @ToString.Exclude
  private String paymentToken;

  private String cardBrand;
  private String cardLast4;
  private String cardExpiration;

  private BigDecimal total;

  private List<Taco> tacos = new ArrayList<>();

  public void addTaco(Taco design) {
    this.tacos.add(design);
  }

  // Métodos de compatibilidad seguros: no almacenan PAN ni CVV
  public String getCcNumber() {
    return paymentToken;
  }

  public void setCcNumber(String tokenOrPan) {
    if (tokenOrPan != null && tokenOrPan.startsWith("tok_")) {
      this.paymentToken = tokenOrPan;
    } else if (tokenOrPan != null && !tokenOrPan.isEmpty()) {
      this.paymentToken = "tok_" + Math.abs(tokenOrPan.hashCode());
      this.cardLast4 = tokenOrPan.length() >= 4 ? tokenOrPan.substring(tokenOrPan.length() - 4) : "0000";
      this.cardBrand = tokenOrPan.startsWith("4") ? "VISA" : "MASTERCARD";
    }
  }

  public String getCcExpiration() {
    return cardExpiration;
  }

  public void setCcExpiration(String ccExpiration) {
    this.cardExpiration = ccExpiration;
  }

  public String getCcCVV() {
    return null;
  }

  public void setCcCVV(String ccCVV) {
    // CVV se descarta estrictamente y nunca se almacena
  }

}
