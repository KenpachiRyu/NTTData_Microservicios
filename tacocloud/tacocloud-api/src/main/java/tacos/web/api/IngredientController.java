package tacos.web.api;

import java.net.URI;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.data.IngredientRepository;

@RestController
@RequestMapping(path="/api/ingredients", produces="application/json")
@CrossOrigin(origins="*")
public class IngredientController {

  private IngredientRepository repo;

  @Autowired
  public IngredientController(IngredientRepository repo) {
    this.repo = repo;
  }

  @GetMapping
  public Flux<Ingredient> allIngredients() {
    return repo.findAll();
  }

  @GetMapping("/{id}")
  public Mono<Ingredient> byId(@PathVariable String id) {
    return repo.findById(id);
  }

  @PutMapping("/{id}")
  public Mono<ResponseEntity<Ingredient>> updateIngredient(@PathVariable String id, @RequestBody Ingredient ingredient) {
    if (ingredient.getId() != null && !ingredient.getId().equals(id)) {
      return Mono.just(new ResponseEntity<>(HttpStatus.BAD_REQUEST));
    }
    return repo.findById(id)
        .flatMap(existing -> repo.save(ingredient))
        .map(saved -> new ResponseEntity<>(saved, HttpStatus.OK))
        .defaultIfEmpty(new ResponseEntity<>(HttpStatus.NOT_FOUND));
  }

  @PostMapping(consumes="application/json")
  public Mono<ResponseEntity<Ingredient>> postIngredient(
      @RequestBody Mono<Ingredient> ingredientMono,
      ServerHttpRequest request) {
    return ingredientMono
        .flatMap(ingredient -> {
          if (ingredient.getName() == null || ingredient.getName().trim().isEmpty() || ingredient.getType() == null) {
            return Mono.just(new ResponseEntity<Ingredient>(HttpStatus.BAD_REQUEST));
          }
          return repo.save(ingredient)
              .map(saved -> {
                URI location = UriComponentsBuilder.fromHttpRequest(request)
                    .pathSegment(saved.getId())
                    .build()
                    .toUri();
                return ResponseEntity.created(location).body(saved);
              });
        })
        .defaultIfEmpty(new ResponseEntity<>(HttpStatus.BAD_REQUEST));
  }

  @DeleteMapping("/{id}")
  public Mono<ResponseEntity<Void>> deleteIngredient(@PathVariable String id) {
    return repo.findById(id)
        .flatMap(existing -> repo.delete(existing)
            .then(Mono.just(new ResponseEntity<Void>(HttpStatus.NO_CONTENT))))
        .defaultIfEmpty(new ResponseEntity<>(HttpStatus.NOT_FOUND));
  }

}