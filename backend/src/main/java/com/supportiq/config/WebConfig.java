package com.supportiq.config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
@Configuration public class WebConfig implements WebMvcConfigurer {
 @Value("${app.cors.origin}") String origin;
 @Override public void addCorsMappings(CorsRegistry r){r.addMapping("/api/**").allowedOrigins(origin).allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS").allowedHeaders("*").allowCredentials(true);}
}
