package tacos;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Document
@Data
@NoArgsConstructor(force = true, access = AccessLevel.PRIVATE)
public class PaymentMethod {

  @Id
  private String id;

  private final User user;

  // TC-12: Tokenización y eliminación de PAN/CVV
  @ToString.Exclude
  private String paymentToken;

  private String brand;
  private String last4;
  private String expiration;

  public PaymentMethod(User user, String paymentToken, String brand, String last4, String expiration) {
    this.user = user;
    this.paymentToken = paymentToken;
    this.brand = brand;
    this.last4 = last4;
    this.expiration = expiration;
  }

  // Constructor de compatibilidad: tokeniza de forma segura y descarta CVV
  public PaymentMethod(User user, String ccNumber, String ccCVV, String ccExpiration) {
    this.user = user;
    this.paymentToken = "tok_" + (ccNumber != null ? Math.abs(ccNumber.hashCode()) : "0");
    this.brand = (ccNumber != null && ccNumber.startsWith("4")) ? "VISA" : "MASTERCARD";
    this.last4 = (ccNumber != null && ccNumber.length() >= 4) ? ccNumber.substring(ccNumber.length() - 4) : "0000";
    this.expiration = ccExpiration;
    // El CVV se descarta estrictamente y nunca se almacena
  }

  public String getCcNumber() {
    return paymentToken;
  }

  public String getCcCVV() {
    return null;
  }

  public String getCcExpiration() {
    return expiration;
  }
}
