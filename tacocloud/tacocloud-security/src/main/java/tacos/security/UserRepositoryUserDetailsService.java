package tacos.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.User;
import tacos.data.UserRepository;

@Service
public class UserRepositoryUserDetailsService 
        implements UserDetailsService {

  private final UserRepository userRepo;

  @Autowired
  public UserRepositoryUserDetailsService(UserRepository userRepo) {
    this.userRepo = userRepo;
  }

  public Mono<UserDetails> findByUsername(String username) {
    return userRepo.findByUsername(username)
        .cast(UserDetails.class)
        .switchIfEmpty(Mono.error(new UsernameNotFoundException("User '" + username + "' not found")));
  }

  @Override
  public UserDetails loadUserByUsername(String username)
      throws UsernameNotFoundException {
    User user = userRepo.findByUsername(username).block();
    if (user != null) {
      return user;
    }
    throw new UsernameNotFoundException("User '" + username + "' not found");
  }

}
