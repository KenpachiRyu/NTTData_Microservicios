package tacos.web.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

@Data
public class OrderPatchRequest {

  private String deliveryName;
  private String deliveryStreet;
  private String deliveryCity;
  private String deliveryState;
  private String deliveryZip;

  @JsonAnySetter
  public void handleUnknown(String key, Object value) {
    throw new IllegalArgumentException("Field '" + key + "' is not allowed in PATCH");
  }

}
