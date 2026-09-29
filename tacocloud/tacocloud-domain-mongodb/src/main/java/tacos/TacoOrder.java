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

  @org.springframework.data.annotation.Version
  private Long version;

  private Date placedAt = new Date();

  private OrderStatus status = OrderStatus.CREATED;
  private String stationId;
  private String cookId;
  private Integer estimatedPrepMinutes;
  private List<OrderStatusChange> statusHistory = new ArrayList<>();

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
  private BigDecimal subtotal;
  private BigDecimal discountAmount;
  private String couponCode;
  private String currency = "USD";

  private List<Taco> tacos = new ArrayList<>();
  private List<OrderItem> items = new ArrayList<>();

  public void addTaco(Taco design) {
    this.tacos.add(design);
  }

  public void addItem(OrderItem item) {
    this.items.add(item);
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

  public void recordStatusChange(OrderStatus from, OrderStatus to, String updatedBy, String source, String reason) {
    this.status = to;
    if (this.statusHistory == null) {
      this.statusHistory = new ArrayList<>();
    }
    this.statusHistory.add(new OrderStatusChange(from, to, new Date(), updatedBy, source, reason));
  }

}
