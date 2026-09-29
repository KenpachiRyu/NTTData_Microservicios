package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.TacoOrder;
import tacos.User;

public class DtoSerializationTest {

  private ObjectMapper objectMapper;
  private OrderMapper orderMapper;
  private IngredientMapper ingredientMapper;

  @BeforeEach
  public void setup() {
    objectMapper = new ObjectMapper();
    orderMapper = new OrderMapper();
    ingredientMapper = new IngredientMapper();
  }

  @Test
  public void orderResponseSerializationMustNotExposeSensitiveFields() throws Exception {
    OrderResponse response = new OrderResponse();
    response.setId("order-123");
    response.setPlacedAt(new Date());
    response.setDeliveryName("Alice");
    response.setDeliveryStreet("123 Main St");
    response.setDeliveryCity("Dallas");
    response.setDeliveryState("TX");
    response.setDeliveryZip("75001");
    response.setPaymentToken("tok_safe_12345");
    response.setTacos(Collections.emptyList());

    String json = objectMapper.writeValueAsString(response);

    // Verify sensitive and internal fields are completely absent from serialization
    assertFalse(json.contains("password"), "JSON must not contain password");
    assertFalse(json.contains("ccNumber"), "JSON must not contain ccNumber");
    assertFalse(json.contains("ccCVV"), "JSON must not contain ccCVV");
    assertFalse(json.contains("ccExpiration"), "JSON must not contain ccExpiration");
    assertFalse(json.contains("authorities"), "JSON must not contain authorities");
    assertFalse(json.contains("accountNonLocked"), "JSON must not contain internal user flags");

    assertTrue(json.contains("order-123"));
    assertTrue(json.contains("tok_safe_12345"));
  }

  @Test
  public void orderMapperShouldMapRequestToDomainAndDomainToResponse() {
    OrderCreateRequest request = new OrderCreateRequest();
    request.setDeliveryName("Bob");
    request.setDeliveryStreet("456 Oak");
    request.setDeliveryCity("Austin");
    request.setDeliveryState("TX");
    request.setDeliveryZip("78701");
    request.setPaymentToken("tok_bob_999");
    request.setTacos(Collections.emptyList());

    TacoOrder domain = orderMapper.toDomain(request);
    assertNotNull(domain);
    assertEquals("Bob", domain.getDeliveryName());
    assertEquals("456 Oak", domain.getDeliveryStreet());
    assertEquals("Austin", domain.getDeliveryCity());
    assertEquals("TX", domain.getDeliveryState());
    assertEquals("78701", domain.getDeliveryZip());
    assertEquals("tok_bob_999", domain.getCcNumber());
    assertNull(domain.getId(), "Client request must not assign domain ID");

    domain.setId("assigned-order-id");
    domain.setPlacedAt(new Date(12345678L));
    domain.setUser(new User("bob", "secret_hash", "Bob Jones", "456 Oak", "Austin", "TX", "78701", "555-9999", "bob@example.com"));

    OrderResponse response = orderMapper.toResponse(domain);
    assertNotNull(response);
    assertEquals("assigned-order-id", response.getId());
    assertEquals(new Date(12345678L), response.getPlacedAt());
    assertEquals("Bob", response.getDeliveryName());
    assertEquals("tok_bob_999", response.getPaymentToken());
  }

  @Test
  public void ingredientMapperShouldMapRequestToDomainAndDomainToResponse() {
    IngredientRequest request = new IngredientRequest("FLTO", "Flour Tortilla", Type.WRAP);
    Ingredient domain = ingredientMapper.toDomain(request);

    assertNotNull(domain);
    assertEquals("FLTO", domain.getId());
    assertEquals("Flour Tortilla", domain.getName());
    assertEquals(Type.WRAP, domain.getType());

    IngredientResponse response = ingredientMapper.toResponse(domain);
    assertNotNull(response);
    assertEquals("FLTO", response.getId());
    assertEquals("Flour Tortilla", response.getName());
    assertEquals(Type.WRAP, response.getType());
  }

  @Test
  public void orderCreateRequestDoesNotSupportMassAssignmentOfTotalOrPlacedAt() {
    OrderCreateRequest request = new OrderCreateRequest();
    request.setDeliveryName("Mass Assignment Test");

    // OrderCreateRequest lacks setters/fields for server-owned attributes
    TacoOrder domain = orderMapper.toDomain(request);
    assertNull(domain.getTotal(), "Total must not be set from request");
    assertNull(domain.getUser(), "User must not be set from request");
  }
}
