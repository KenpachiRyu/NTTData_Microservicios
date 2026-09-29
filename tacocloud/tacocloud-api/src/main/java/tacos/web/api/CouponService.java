package tacos.web.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Service
public class CouponService {

  private final CouponProperties couponProperties;
  private final Clock clock;

  @Autowired
  public CouponService(CouponProperties couponProperties, @Autowired(required = false) Clock clock) {
    this.couponProperties = couponProperties;
    this.clock = clock != null ? clock : Clock.systemDefaultZone();
  }

  public DiscountResult applyCoupon(String code, BigDecimal subtotal) {
    if (subtotal == null || subtotal.compareTo(BigDecimal.ZERO) <= 0) {
      return DiscountResult.invalid("El subtotal debe ser mayor a cero para aplicar un cupón", BigDecimal.ZERO);
    }

    if (code == null || code.trim().isEmpty()) {
      return DiscountResult.noCoupon(subtotal);
    }

    String normalized = code.trim().toUpperCase();
    CouponDefinition coupon = couponProperties.findCoupon(normalized);

    if (coupon == null) {
      return DiscountResult.invalid("El código de cupón no es válido", subtotal);
    }

    LocalDate today = LocalDate.now(clock);

    if (coupon.getValidFrom() != null && today.isBefore(coupon.getValidFrom())) {
      return DiscountResult.invalid("El cupón aún no está activo", subtotal);
    }

    if (coupon.getValidTo() != null && today.isAfter(coupon.getValidTo())) {
      return DiscountResult.invalid("El cupón ha expirado", subtotal);
    }

    if (coupon.getMinOrderAmount() != null && subtotal.compareTo(coupon.getMinOrderAmount()) < 0) {
      return DiscountResult.invalid(
          "El pedido no alcanza el monto mínimo de compra requerido ($" + coupon.getMinOrderAmount() + ")",
          subtotal);
    }

    BigDecimal discount = BigDecimal.ZERO;
    if (coupon.getType() == DiscountType.PERCENTAGE) {
      discount = subtotal.multiply(coupon.getAmount())
          .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    } else if (coupon.getType() == DiscountType.FIXED) {
      discount = coupon.getAmount().setScale(2, RoundingMode.HALF_UP);
    }

    // Limitar al descuento máximo si está configurado
    if (coupon.getMaxDiscountAmount() != null && discount.compareTo(coupon.getMaxDiscountAmount()) > 0) {
      discount = coupon.getMaxDiscountAmount().setScale(2, RoundingMode.HALF_UP);
    }

    // Regla obligatoria: El descuento nunca puede volver negativo el total
    if (discount.compareTo(subtotal) > 0) {
      discount = subtotal;
    }

    BigDecimal finalTotal = subtotal.subtract(discount).setScale(2, RoundingMode.HALF_UP);
    return DiscountResult.valid(normalized, discount, finalTotal);
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class DiscountResult {
    private boolean valid;
    private String code;
    private BigDecimal discountAmount = BigDecimal.ZERO;
    private BigDecimal finalTotal = BigDecimal.ZERO;
    private String message;

    public static DiscountResult valid(String code, BigDecimal discount, BigDecimal total) {
      return new DiscountResult(true, code, discount, total, "Cupón aplicado exitosamente");
    }

    public static DiscountResult invalid(String message, BigDecimal subtotal) {
      return new DiscountResult(false, null, BigDecimal.ZERO, subtotal, message);
    }

    public static DiscountResult noCoupon(BigDecimal subtotal) {
      return new DiscountResult(true, null, BigDecimal.ZERO, subtotal, "Sin cupón aplicado");
    }
  }
}
