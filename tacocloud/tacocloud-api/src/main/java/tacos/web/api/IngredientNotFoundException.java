package tacos.web.api;

public class IngredientNotFoundException extends RuntimeException {
  public IngredientNotFoundException(String ingredientId) {
    super("Ingredient not found with ID: " + ingredientId);
  }
}
