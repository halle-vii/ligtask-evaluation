package ph.ligtask;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
@SpringBootApplication
public class Application {
 public static void main(String[] args) { SpringApplication.run(Application.class,args); }
 @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
  return http.authorizeHttpRequests(a->a.anyRequest().permitAll()).build();
 }
}
