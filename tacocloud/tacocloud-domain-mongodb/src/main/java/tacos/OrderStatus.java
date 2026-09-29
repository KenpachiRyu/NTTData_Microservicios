package tacos;

public enum OrderStatus {
  CREATED,
  ACCEPTED,
  PREPARING,
  READY,
  OUT_FOR_DELIVERY,
  DELIVERED,
  CANCELLED;

  public boolean isTerminal() {
    return this == DELIVERED || this == CANCELLED;
  }
}
