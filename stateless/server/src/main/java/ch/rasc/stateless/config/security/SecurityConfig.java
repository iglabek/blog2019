package ch.rasc.stateless.config.security;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletResponse;

import org.jooq.DSLContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;

import ch.rasc.stateless.config.AppProperties;

@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig extends WebSecurityConfigurerAdapter {

  private static final DateTimeFormatter COOKIE_DATE_FORMATTER = DateTimeFormatter
					.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'").localizedBy(Locale.ENGLISH);

  private final AppProperties appProperties;

  private final CryptoService cryptoService;

  private final AuthCookieFilter authCookieFilter;

  public SecurityConfig(AppProperties appProperties, CryptoService cryptoService,
      DSLContext dsl) {
    this.appProperties = appProperties;
    this.cryptoService = cryptoService;
    this.authCookieFilter = new AuthCookieFilter(dsl, cryptoService);
  }

  @Override
  protected void configure(HttpSecurity http) throws Exception {
    // @formatter:off
      http
        .sessionManagement()
      	.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
      .and()
        .headers().contentSecurityPolicy("script-src 'self'; object-src 'none'; base-uri 'self'").and()
      .and()
        .csrf().disable()
  	.formLogin()
  	  .successHandler(formLoginSuccessHandler())
  	  .failureHandler((request, response, exception) ->
  	                  response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED"))
  	  .permitAll()
      .and()
  	.logout()
  	  .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler())
  	  .deleteCookies(AuthCookieFilter.COOKIE_NAME)
  	  .permitAll()
      .and()
	  .authorizeRequests().anyRequest().authenticated()
      .and()
        .exceptionHandling()
          .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
      .and()
        .addFilterBefore(this.authCookieFilter, UsernamePasswordAuthenticationFilter.class);
      // @formatter:on
  }

  private AuthenticationSuccessHandler formLoginSuccessHandler() {
    return (request, response, authentication) -> {
      JooqUserDetails userDetails = (JooqUserDetails) authentication.getPrincipal();

      List<String> headerValues = new ArrayList<>();

      String cookieValue = userDetails.getUserDbId() + ":";
      if (this.appProperties.getCookieMaxAge() != null) {
        cookieValue += Instant.now().plus(this.appProperties.getCookieMaxAge())
            .getEpochSecond();
      }
      else {
        // default max age of 4h
        cookieValue += Instant.now().plus(Duration.ofHours(4)).getEpochSecond();
      }

      String encryptedCookieValue = SecurityConfig.this.cryptoService
          .encrypt(cookieValue);
      headerValues.add(AuthCookieFilter.COOKIE_NAME + "=" + encryptedCookieValue);

      if (this.appProperties.getCookieMaxAge() != null) {
        long maxAgeInSeconds = this.appProperties.getCookieMaxAge().toSeconds();
        if (maxAgeInSeconds > -1) {
          headerValues.add("Max-Age=" + maxAgeInSeconds);

          if (maxAgeInSeconds == 0) {
            headerValues.add("Expires=" + COOKIE_DATE_FORMATTER.format(
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(10000), ZoneOffset.UTC)));
          }
          else {
            headerValues.add("Expires=" + COOKIE_DATE_FORMATTER
                .format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(maxAgeInSeconds)));
          }
        }
      }

      headerValues.add("SameSite=Strict");
      headerValues.add("Path=/");
      headerValues.add("HttpOnly");
      if (this.appProperties.isSecureCookie()) {
        headerValues.add("Secure");
      }

      response.addHeader("Set-Cookie",
          headerValues.stream().collect(Collectors.joining("; ")));

      response.getWriter().print(SecurityContextHolder.getContext().getAuthentication()
          .getAuthorities().iterator().next().getAuthority());
    };
  }

}
