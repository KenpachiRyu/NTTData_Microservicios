package tacos.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@SuppressWarnings("deprecation")
@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {

  @Autowired
  private UserDetailsService userDetailsService;

  // =========================================================================
  // TC-10: PasswordEncoder adaptativo seguro (reemplaza NoOpPasswordEncoder) (8 pts)
  // =========================================================================
  @Bean
  public PasswordEncoder encoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Override
  protected void configure(AuthenticationManagerBuilder auth) throws Exception {
    auth
      .userDetailsService(userDetailsService)
      .passwordEncoder(encoder());
  }

  // =========================================================================
  // TC-11: Matriz de autorización deny-by-default y roles útiles (13 pts)
  // =========================================================================
  @Override
  protected void configure(HttpSecurity http) throws Exception {
    http
      .csrf()
        .ignoringAntMatchers("/h2-console/**", "/api/**", "/register")
      .and()
      .headers()
        .frameOptions().sameOrigin()
      .and()
      .authorizeRequests()
        .antMatchers(HttpMethod.OPTIONS).permitAll() // Requerido para CORS/Angular
        
        // --- PERMITIR VISTAS HTML Y RECURSOS ESTÁTICOS ---
        .antMatchers("/", "/login", "/register", "/styles/**", "/images/**", "/*.css", "/*.js", "/favicon.ico").permitAll()
        
        // Lectura pública del catálogo de la API
        .antMatchers(HttpMethod.GET, "/api/ingredients/**", "/api/tacos/**").permitAll()
        
        // Pedidos y favoritos requieren rol USER
        .antMatchers("/api/orders/**", "/api/users/me/**").hasRole("USER")
        
        // Edición de catálogo requiere ADMIN
        .antMatchers(HttpMethod.POST, "/api/ingredients/**").hasRole("ADMIN")
        .antMatchers(HttpMethod.PUT, "/api/ingredients/**").hasRole("ADMIN")
        .antMatchers(HttpMethod.DELETE, "/api/ingredients/**").hasRole("ADMIN")
        
        // Cocina requiere KITCHEN
        .antMatchers("/api/kitchen/**").hasRole("KITCHEN")
        
        // DENY-BY-DEFAULT: Cualquier otra ruta no listada se bloquea
        .anyRequest().denyAll()
      .and()
      .formLogin()
        .loginPage("/login")
        .defaultSuccessUrl("/", true)
      .and()
      .httpBasic()
        .realmName("Taco Cloud")
      .and()
      .logout()
        .logoutSuccessUrl("/");
  }
}