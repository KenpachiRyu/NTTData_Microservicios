package tacos.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.User;
import tacos.data.UserRepository;

public class RegistrationControllerTest {

  private UserRepository userRepo;
  private PasswordEncoder passwordEncoder;
  private RegistrationController controller;

  @BeforeEach
  public void setup() {
    userRepo = Mockito.mock(UserRepository.class);
    passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    controller = new RegistrationController(userRepo, passwordEncoder);
  }

  @Test
  public void shouldEncodePasswordWithDelegatingBcryptAndPersistReactively() {
    RegistrationForm form = new RegistrationForm();
    form.setUsername("newuser");
    form.setPassword("plainSecret123");
    form.setFullname("New User");
    form.setStreet("123 Street");
    form.setCity("City");
    form.setState("ST");
    form.setZip("12345");
    form.setPhone("555-0000");
    form.setEmail("newuser@example.com");

    when(userRepo.findByUsername("newuser")).thenReturn(Mono.empty());
    when(userRepo.findByEmail("newuser@example.com")).thenReturn(Mono.empty());
    when(userRepo.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    Mono<ResponseEntity<Void>> responseMono = controller.processFormRegistration(form);

    StepVerifier.create(responseMono)
        .assertNext(entity -> {
          assertEquals(HttpStatus.CREATED, entity.getStatusCode());
          assertEquals("/login", entity.getHeaders().getFirst("Location"));
        })
        .verifyComplete();

    ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
    verify(userRepo).save(captor.capture());

    User savedUser = captor.getValue();
    assertEquals("newuser", savedUser.getUsername());
    assertNotEquals("plainSecret123", savedUser.getPassword(), "Password must not be stored in plaintext");
    assertTrue(savedUser.getPassword().startsWith("{bcrypt}"), "Password hash must include {bcrypt} prefix");
    assertTrue(passwordEncoder.matches("plainSecret123", savedUser.getPassword()), "Encoder must match plain password with hash");
  }

  @Test
  public void shouldReturn409WhenUsernameAlreadyExists() {
    RegistrationForm form = new RegistrationForm();
    form.setUsername("existinguser");
    form.setPassword("secret");

    User existing = new User("existinguser", "hash", "Existing", "Street", "City", "ST", "12345", "555-1111", "existing@example.com");
    when(userRepo.findByUsername("existinguser")).thenReturn(Mono.just(existing));

    Mono<ResponseEntity<Void>> responseMono = controller.processFormRegistration(form);

    StepVerifier.create(responseMono)
        .assertNext(entity -> assertEquals(HttpStatus.CONFLICT, entity.getStatusCode()))
        .verifyComplete();

    verify(userRepo, never()).save(any());
  }

  @Test
  public void shouldReturn409WhenEmailAlreadyExists() {
    RegistrationForm form = new RegistrationForm();
    form.setUsername("uniqueuser");
    form.setPassword("secret");
    form.setEmail("taken@example.com");

    when(userRepo.findByUsername("uniqueuser")).thenReturn(Mono.empty());
    User existingByEmail = new User("otheruser", "hash", "Other", "Street", "City", "ST", "12345", "555-1111", "taken@example.com");
    when(userRepo.findByEmail("taken@example.com")).thenReturn(Mono.just(existingByEmail));

    Mono<ResponseEntity<Void>> responseMono = controller.processFormRegistration(form);

    StepVerifier.create(responseMono)
        .assertNext(entity -> assertEquals(HttpStatus.CONFLICT, entity.getStatusCode()))
        .verifyComplete();

    verify(userRepo, never()).save(any());
  }

  @Test
  public void shouldHandleDuplicateKeyExceptionGracefullyWith409() {
    RegistrationForm form = new RegistrationForm();
    form.setUsername("concurrentuser");
    form.setPassword("secret");

    when(userRepo.findByUsername("concurrentuser")).thenReturn(Mono.empty());
    when(userRepo.save(any())).thenReturn(Mono.error(new DuplicateKeyException("E11000 duplicate key error")));

    Mono<ResponseEntity<Void>> responseMono = controller.processFormRegistration(form);

    StepVerifier.create(responseMono)
        .assertNext(entity -> assertEquals(HttpStatus.CONFLICT, entity.getStatusCode()))
        .verifyComplete();

    verify(userRepo).save(any());
  }

  @Test
  public void shouldSupportDynamicRolesWithoutHardcoding() {
    User user = new User("customuser", "hash", "Custom", "Street", "City", "ST", "12345", "555-1111", "custom@example.com");
    user.setRoles(Arrays.asList("ADMIN", "KITCHEN"));

    Collection<? extends GrantedAuthority> authorities = user.getAuthorities();
    assertEquals(2, authorities.size());
    assertTrue(authorities.stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    assertTrue(authorities.stream().anyMatch(a -> a.getAuthority().equals("ROLE_KITCHEN")));
  }
}
