package tacos.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import reactor.core.publisher.Mono;
import tacos.data.UserRepository;

@Controller
@RequestMapping("/register")
public class RegistrationController {

  private UserRepository userRepo;
  private PasswordEncoder passwordEncoder;

  @Autowired
  public RegistrationController(UserRepository userRepo, PasswordEncoder passwordEncoder) {
    this.userRepo = userRepo;
    this.passwordEncoder = passwordEncoder;
  }

  @GetMapping
  public String registerForm() {
    return "registration";
  }

  // =========================================================================
  // TC-10: Registro reactivo encadenado con contraseñas protegidas (8 pts)
  // =========================================================================
  @PostMapping(consumes = "application/json")
  @ResponseBody
  public Mono<ResponseEntity<Void>> processJsonRegistration(@RequestBody RegistrationForm form) {
    return handleRegistration(form);
  }

  @PostMapping
  public Mono<ResponseEntity<Void>> processFormRegistration(RegistrationForm form) {
    return handleRegistration(form);
  }

  private Mono<ResponseEntity<Void>> handleRegistration(RegistrationForm form) {
    if (form == null || form.getUsername() == null || form.getUsername().trim().isEmpty() ||
        form.getPassword() == null || form.getPassword().trim().isEmpty()) {
      return Mono.just(new ResponseEntity<Void>(HttpStatus.BAD_REQUEST));
    }

    return userRepo.findByUsername(form.getUsername())
        .map(u -> true)
        .defaultIfEmpty(false)
        .flatMap(usernameExists -> {
          if (usernameExists) {
            return Mono.just(new ResponseEntity<Void>(HttpStatus.CONFLICT));
          }
          if (form.getEmail() != null && !form.getEmail().trim().isEmpty()) {
            return userRepo.findByEmail(form.getEmail())
                .map(u -> true)
                .defaultIfEmpty(false)
                .flatMap(emailExists -> {
                  if (emailExists) {
                    return Mono.just(new ResponseEntity<Void>(HttpStatus.CONFLICT));
                  }
                  return saveUser(form);
                });
          }
          return saveUser(form);
        });
  }

  private Mono<ResponseEntity<Void>> saveUser(RegistrationForm form) {
    return userRepo.save(form.toUser(passwordEncoder))
        .map(saved -> {
          HttpHeaders headers = new HttpHeaders();
          headers.setLocation(java.net.URI.create("/login"));
          return new ResponseEntity<Void>(headers, HttpStatus.CREATED);
        })
        .onErrorResume(DuplicateKeyException.class,
            ex -> Mono.just(new ResponseEntity<Void>(HttpStatus.CONFLICT)));
  }

}