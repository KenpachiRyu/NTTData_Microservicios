package tacos.web.api;

import java.security.Principal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.TacoFavorite;

@RestController
@RequestMapping(path = "/api/users/me/favorites", produces = "application/json")
@CrossOrigin(origins = "*")
public class UserFavoritesController {

  private final FavoriteService favoriteService;

  @Autowired
  public UserFavoritesController(FavoriteService favoriteService) {
    this.favoriteService = favoriteService;
  }

  @PutMapping("/{tacoId}")
  public Mono<ResponseEntity<TacoFavorite>> addFavorite(
      @PathVariable String tacoId,
      Principal principal) {
    if (principal == null) {
      return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }
    return favoriteService.addFavorite(principal.getName(), tacoId)
        .map(fav -> ResponseEntity.ok(fav));
  }

  @DeleteMapping("/{tacoId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> removeFavorite(
      @PathVariable String tacoId,
      Principal principal) {
    if (principal == null) {
      return Mono.empty();
    }
    return favoriteService.removeFavorite(principal.getName(), tacoId);
  }

  @GetMapping
  public Mono<PageResponse<Taco>> listFavorites(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      Principal principal) {
    if (principal == null) {
      return Mono.error(new BusinessRuleException("Usuario no autenticado", "UNAUTHORIZED"));
    }
    return favoriteService.getFavorites(principal.getName(), page, size);
  }
}
