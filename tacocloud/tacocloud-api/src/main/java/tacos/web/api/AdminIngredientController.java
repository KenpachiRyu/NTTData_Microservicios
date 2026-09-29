package tacos.web.api;

import java.math.BigDecimal;
import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.data.IngredientRepository;

@RestController
@RequestMapping(path = "/api/admin/ingredients", produces = "application/json")
@CrossOrigin(origins = "*")
public class AdminIngredientController {

  private final IngredientRepository repo;

  @Autowired
  public AdminIngredientController(IngredientRepository repo) {
    this.repo = repo;
  }

  @PatchMapping("/{id}/catalog")
  public Mono<ResponseEntity<AdminIngredientResponse>> patchCatalog(
      @PathVariable String id,
      @Valid @RequestBody IngredientCatalogPatchRequest request) {

    return repo.findById(id)
        .flatMap(ingredient -> {
          if (request.getUnitPrice() != null) {
            if (request.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
              return Mono.error(new BusinessRuleException("El precio unitario no puede ser negativo"));
            }
            ingredient.setUnitPrice(request.getUnitPrice());
          }
          if (request.getAvailable() != null) {
            ingredient.setAvailable(request.getAvailable());
          }
          if (request.getReorderLevel() != null) {
            if (request.getReorderLevel() < 0) {
              return Mono.error(new BusinessRuleException("El nivel de reorden no puede ser negativo"));
            }
            ingredient.setReorderLevel(request.getReorderLevel());
          }
          if (request.getVersion() != null) {
            ingredient.setVersion(request.getVersion());
          }
          return repo.save(ingredient);
        })
        .map(saved -> ResponseEntity.ok(AdminIngredientResponse.fromDomain(saved)))
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  @PostMapping("/{id}/stock-adjustments")
  public Mono<ResponseEntity<AdminIngredientResponse>> adjustStock(
      @PathVariable String id,
      @Valid @RequestBody StockAdjustmentRequest request) {

    return repo.findById(id)
        .flatMap(ingredient -> {
          int currentStock = ingredient.getStockOnHand() != null ? ingredient.getStockOnHand() : 0;
          int newStock = currentStock + request.getAdjustment();

          if (newStock < 0) {
            return Mono.error(new BusinessRuleException("El ajuste produciría un stock negativo (" + newStock + ") para el ingrediente: " + id));
          }

          ingredient.setStockOnHand(newStock);
          if (request.getVersion() != null) {
            ingredient.setVersion(request.getVersion());
          }
          return repo.save(ingredient);
        })
        .map(saved -> ResponseEntity.ok(AdminIngredientResponse.fromDomain(saved)))
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }
}
