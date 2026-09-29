package tacos;

public enum SpiceLevel {
  NONE(0),
  MILD(1),
  MEDIUM(2),
  HOT(3),
  EXTRA_HOT(4);

  private final int heatRank;

  SpiceLevel(int heatRank) {
    this.heatRank = heatRank;
  }

  public int getHeatRank() {
    return heatRank;
  }
}
