package tacos.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
  // TC-10: Registro reactivo encadenado (8 pts)
  // =========================================================================
  @PostMapping
  public Mono<String> processRegistration(RegistrationForm form) {
    // Retornamos el Mono encadenado con map para asegurar que el save() se ejecute en Mongo
    return userRepo.save(form.toUser(passwordEncoder))
        .map(user -> "redirect:/login");
  }

}