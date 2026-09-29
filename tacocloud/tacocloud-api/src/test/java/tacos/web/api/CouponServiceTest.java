package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

public class CouponServiceTest {

  private CouponProperties props;
  private Clock fixedClock;
  private CouponService couponService;
  private CouponApiController couponController;
  private WebTestClient testClient;

  @BeforeEach
  public void setUp() {
    props = new CouponProperties();

    // 1 de Julio de 2026 a las 12:00 UTC
    Instant fixedInstant = Instant.parse("2026-07-01T12:00:00Z");
    fixedClock = Clock.fixed(fixedInstant, ZoneId.of("UTC"));

    // Cupón porcentual 20%
    CouponDefinition promo20 = new CouponDefinition(
        "PROMO20",
        DiscountType.PERCENTAGE,
        new BigDecimal("20"),
        new BigDecimal("10.00"), // mínimo 10
        new BigDecimal("15.00"), // max descuento 15
        LocalDate.of(2026, 6, 1),
        LocalDate.of(2026, 8, 1)
    );
    props.addCoupon(promo20);

    // Cupón fijo $5
    CouponDefinition save5 = new CouponDefinition(
        "SAVE5",
        DiscountType.FIXED,
        new BigDecimal("5.00"),
        new BigDecimal("10.00"),
        null,
        LocalDate.of(2026, 6, 1),
        LocalDate.of(2026, 8, 1)
    );
    props.addCoupon(save5);

    // Cupón expirado
    CouponDefinition expired = new CouponDefinition(
        "EXPIRED",
        DiscountType.PERCENTAGE,
        new BigDecimal("50"),
        BigDecimal.ZERO,
        null,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 5, 31) // Expiró en Mayo
    );
    props.addCoupon(expired);

    couponService = new CouponService(props, fixedClock);
    couponController = new CouponApiController(couponService, null);
    testClient = WebTestClient.bindToController(couponController)
        .controllerAdvice(new RestExceptionHandler())
        .build();
  }

  @Test
  public void shouldApplyPercentageCouponSuccessfully() {
    CouponService.DiscountResult result = couponService.applyCoupon("promo20", new BigDecimal("25.00"));

    assertTrue(result.isValid());
    assertEquals("PROMO20", result.getCode());
    assertEquals(new BigDecimal("5.00"), result.getDiscountAmount()); // 20% de 25 = 5.00
    assertEquals(new BigDecimal("20.00"), result.getFinalTotal());
  }

  @Test
  public void shouldApplyFixedCouponSuccessfully() {
    CouponService.DiscountResult result = couponService.applyCoupon("SAVE5", new BigDecimal("30.00"));

    assertTrue(result.isValid());
    assertEquals("SAVE5", result.getCode());
    assertEquals(new BigDecimal("5.00"), result.getDiscountAmount());
    assertEquals(new BigDecimal("25.00"), result.getFinalTotal());
  }

  @Test
  public void shouldCapDiscountAtMaxDiscountAmount() {
    // 20% de 100 es 20, pero maxDiscount es 15
    CouponService.DiscountResult result = couponService.applyCoupon("PROMO20", new BigDecimal("100.00"));

    assertTrue(result.isValid());
    assertEquals(new BigDecimal("15.00"), result.getDiscountAmount());
    assertEquals(new BigDecimal("85.00"), result.getFinalTotal());
  }

  @Test
  public void shouldRejectExpiredCouponUsingClock() {
    CouponService.DiscountResult result = couponService.applyCoupon("EXPIRED", new BigDecimal("40.00"));

    assertFalse(result.isValid());
    assertTrue(result.getMessage().contains("expirado"));
    assertEquals(BigDecimal.ZERO, result.getDiscountAmount());
    assertEquals(new BigDecimal("40.00"), result.getFinalTotal());
  }

  @Test
  public void shouldRejectWhenMinOrderAmountNotMet() {
    // PROMO20 requiere mínimo $10.00
    CouponService.DiscountResult result = couponService.applyCoupon("PROMO20", new BigDecimal("8.00"));

    assertFalse(result.isValid());
    assertTrue(result.getMessage().contains("monto mínimo"));
  }

  @Test
  public void shouldNeverAllowNegativeTotal() {
    CouponDefinition hugeDiscount = new CouponDefinition(
        "HUGE",
        DiscountType.FIXED,
        new BigDecimal("50.00"),
        BigDecimal.ZERO,
        null,
        null,
        null
    );
    props.addCoupon(hugeDiscount);

    CouponService.DiscountResult result = couponService.applyCoupon("HUGE", new BigDecimal("30.00"));

    assertTrue(result.isValid());
    assertEquals(new BigDecimal("30.00"), result.getDiscountAmount()); // Topeado al subtotal
    assertEquals(new BigDecimal("0.00"), result.getFinalTotal());
  }

  @Test
  public void shouldRejectUnknownCouponWithoutEnumeratingValidCoupons() {
    CouponService.DiscountResult result = couponService.applyCoupon("SECRET99", new BigDecimal("25.00"));

    assertFalse(result.isValid());
    assertEquals("El código de cupón no es válido", result.getMessage());
    assertNull(result.getCode());
  }

  @Test
  public void shouldValidateCouponViaEndpoint() {
    CouponValidateRequest request = new CouponValidateRequest("PROMO20", new BigDecimal("50.00"));

    testClient.post()
        .uri("/api/coupons/validate")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.valid").isEqualTo(true)
        .jsonPath("$.code").isEqualTo("PROMO20")
        .jsonPath("$.discountAmount").isEqualTo(10.00) // 20% de 50 = 10
        .jsonPath("$.finalTotal").isEqualTo(40.00);
  }
}
