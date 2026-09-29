package tacos.web.api;

import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.TacoOfTheDayConfig;

@RestController
@RequestMapping(path = "/api/admin/tacos", produces = "application/json")
@CrossOrigin(origins = "*")
public class AdminTacoController {

  private final TacoOfTheDayService tacoOfTheDayService;

  @Autowired
  public AdminTacoController(TacoOfTheDayService tacoOfTheDayService) {
    this.tacoOfTheDayService = tacoOfTheDayService;
  }

  @PutMapping("/today/{tacoId}")
  public Mono<ResponseEntity<TacoOfTheDayConfig>> setTacoOfTheDay(
      @PathVariable String tacoId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(required = false) String reason) {
    return tacoOfTheDayService.setTacoOfTheDay(tacoId, date, reason)
        .map(ResponseEntity::ok);
  }
}
