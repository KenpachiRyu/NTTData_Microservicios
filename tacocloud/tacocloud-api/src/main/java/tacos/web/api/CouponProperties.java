package tacos.web.api;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Data;

@Component
@ConfigurationProperties(prefix = "taco.discount")
@Data
public class CouponProperties {

  // Soporte para mapa legacy de application.yml (código -> porcentaje)
  private Map<String, Integer> codes = new HashMap<>();

  // Mapa avanzado de cupones con reglas, tipo, fechas y límites
  private Map<String, CouponDefinition> coupons = new HashMap<>();

  @PostConstruct
  public void init() {
    // Si existen códigos legacy en 'codes', convertirlos en cupones PERCENTAGE
    if (codes != null) {
      for (Map.Entry<String, Integer> entry : codes.entrySet()) {
        String normalizedCode = entry.getKey().trim().toUpperCase();
        if (!coupons.containsKey(normalizedCode)) {
          CouponDefinition def = new CouponDefinition();
          def.setCode(normalizedCode);
          def.setType(DiscountType.PERCENTAGE);
          def.setAmount(BigDecimal.valueOf(entry.getValue()));
          coupons.put(normalizedCode, def);
        }
      }
    }
  }

  public CouponDefinition findCoupon(String code) {
    if (code == null) {
      return null;
    }
    String normalized = code.trim().toUpperCase();
    return coupons.get(normalized);
  }

  public void addCoupon(CouponDefinition coupon) {
    if (coupon != null && coupon.getCode() != null) {
      coupons.put(coupon.getCode().trim().toUpperCase(), coupon);
    }
  }
}
