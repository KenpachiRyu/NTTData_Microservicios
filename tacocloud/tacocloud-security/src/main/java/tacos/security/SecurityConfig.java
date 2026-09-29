package tacos.security;

import java.util.Arrays;

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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

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

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(Arrays.asList("http://localhost:8080", "http://localhost:4200", "https://tacocloud.com"));
    configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Requested-With", "Accept", "Origin"));
    configuration.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  // =========================================================================
  // TC-11: Matriz de autorización deny-by-default y roles útiles (13 pts)
  // =========================================================================
  @Override
  protected void configure(HttpSecurity http) throws Exception {
    http
      .cors().and()
      .csrf()
        .ignoringAntMatchers("/h2-console/**", "/api/**", "/register")
      .and()
      .headers()
        .frameOptions().sameOrigin()
      .and()
      .authorizeRequests()
        .antMatchers(HttpMethod.OPTIONS).permitAll()
        
        // --- Vistas públicas y recursos estáticos ---
        .antMatchers("/", "/login", "/register", "/styles/**", "/images/**", "/*.css", "/*.js", "/favicon.ico").permitAll()
        
        // --- Actuator health público; resto de actuator y data-api protegido para ADMIN ---
        .antMatchers("/actuator/health").permitAll()
        .antMatchers("/actuator/**", "/data-api/**").hasRole("ADMIN")
        
        // --- Catálogo público de solo lectura ---
        .antMatchers(HttpMethod.GET, "/api/ingredients/**", "/api/tacos/**").permitAll()
        
        // --- Edición de catálogo y operaciones de administración requieren ADMIN ---
        .antMatchers(HttpMethod.POST, "/api/ingredients/**").hasRole("ADMIN")
        .antMatchers(HttpMethod.PUT, "/api/ingredients/**").hasRole("ADMIN")
        .antMatchers(HttpMethod.DELETE, "/api/ingredients/**").hasRole("ADMIN")
        .antMatchers("/api/admin/**").hasRole("ADMIN")
        
        // --- Pedidos, cupones, validaciones y pagos: USER crea y consulta; ADMIN puede auditar ---
        .antMatchers("/api/orders/**", "/api/users/me/**", "/api/payment-methods/**", "/api/coupons/**", "/api/tacos/validate").hasAnyRole("USER", "ADMIN")
        
        // --- Cocina requiere KITCHEN ---
        .antMatchers("/api/kitchen/**").hasRole("KITCHEN")
        
        // --- DENY-BY-DEFAULT: cualquier otra ruta no listada se bloquea ---
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