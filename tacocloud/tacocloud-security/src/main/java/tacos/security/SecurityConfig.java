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
        
        // --- Vistas públicas y recursos estáticos y documentación OpenAPI ---
        .antMatchers("/", "/login", "/register", "/styles/**", "/images/**", "/*.css", "/*.js", "/favicon.ico",
                     "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
        
        // --- Actuator health público; resto de actuator y data-api protegido para ADMIN ---
        .antMatchers("/actuator/health").permitAll()
        .antMatchers("/actuator/**", "/data-api/**").hasRole("ADMIN")
        
        // --- Catálogo público de solo lectura (con soporte versionado v1) ---
        .antMatchers(HttpMethod.GET, "/api/ingredients/**", "/api/v1/ingredients/**", "/api/tacos/**", "/api/v1/tacos/**").permitAll()
        .antMatchers(HttpMethod.GET, "/api/announcements/**", "/api/v1/announcements/**").permitAll()
        
        // --- Edición de catálogo y operaciones de administración requieren ADMIN ---
        .antMatchers(HttpMethod.POST, "/api/ingredients/**", "/api/v1/ingredients/**").hasRole("ADMIN")
        .antMatchers(HttpMethod.PUT, "/api/ingredients/**", "/api/v1/ingredients/**").hasRole("ADMIN")
        .antMatchers(HttpMethod.DELETE, "/api/ingredients/**", "/api/v1/ingredients/**").hasRole("ADMIN")
        .antMatchers("/api/admin/**", "/api/v1/admin/**").hasRole("ADMIN")
        
        // --- Pedidos, cupones, validaciones, calificaciones y pagos: USER crea y consulta; ADMIN puede auditar ---
        .antMatchers(HttpMethod.PUT, "/api/tacos/*/rating", "/api/v1/tacos/*/rating").hasAnyRole("USER", "ADMIN")
        .antMatchers(HttpMethod.POST, "/api/tacos", "/api/v1/tacos").hasAnyRole("USER", "ADMIN")
        .antMatchers("/api/orders/**", "/api/v1/orders/**",
                     "/api/users/**", "/api/v1/users/**",
                     "/api/payment-methods/**", "/api/v1/payment-methods/**",
                     "/api/coupons/**", "/api/v1/coupons/**",
                     "/api/tacos/validate", "/api/v1/tacos/validate").hasAnyRole("USER", "ADMIN")
        
        // --- Cocina requiere KITCHEN o ADMIN ---
        .antMatchers("/api/kitchen/**", "/api/v1/kitchen/**").hasAnyRole("KITCHEN", "ADMIN")
        
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