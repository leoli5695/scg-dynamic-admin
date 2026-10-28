package com.seckill.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/** Verify the bearer credential at the service boundary. Client-controlled identity headers are ignored. */
@Configuration
@EnableWebSecurity
public class SeckillIdentitySecurity {
    @Bean public JwtDecoder seckillJwtDecoder(SeckillConfig config) throws Exception {
        var identity=config.getIdentity();
        if(identity.getIssuer().isBlank() || identity.getAudience().isBlank()
                || (identity.getPublicKeyPem().isBlank() && identity.getJwkSetUri().isBlank()))
            return token -> {throw new BadJwtException("Seckill identity trust is not configured");};
        NimbusJwtDecoder decoder;
        if(!identity.getPublicKeyPem().isBlank()) {
            String pem=identity.getPublicKeyPem().replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s","");
            RSAPublicKey key=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
            if(key.getModulus().bitLength()<2048)throw new IllegalArgumentException("Identity RSA public key must be at least 2048 bits");
            decoder=NimbusJwtDecoder.withPublicKey(key).build();
        } else {
            var uri=java.net.URI.create(identity.getJwkSetUri());
            if(!"https".equalsIgnoreCase(uri.getScheme()))throw new IllegalArgumentException("Identity JWK URI must use HTTPS");
            var transport=new org.springframework.http.client.SimpleClientHttpRequestFactory();
            transport.setConnectTimeout(2000);transport.setReadTimeout(2000);
            decoder=NimbusJwtDecoder.withJwkSetUri(uri.toString()).restOperations(new org.springframework.web.client.RestTemplate(transport)).build();
        }
        OAuth2TokenValidator<Jwt> claims=jwt -> {
            try {
                Object raw=jwt.getClaims().get(identity.getUserIdClaim());
                long user=Long.parseLong(raw == null ? "" : raw.toString());
                if(user<=0 || jwt.getExpiresAt()==null || !jwt.getExpiresAt().isAfter(Instant.now())
                        || !jwt.getAudience().contains(identity.getAudience()))throw new IllegalArgumentException();
                return OAuth2TokenValidatorResult.success();
            }catch(Exception invalid){return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Invalid seckill identity claims",null));}
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(identity.getIssuer()),claims));
        return decoder;
    }
    @Bean public SecurityFilterChain seckillSecurity(HttpSecurity http,JwtDecoder seckillJwtDecoder,SeckillConfig config) throws Exception {
        var converter=new JwtAuthenticationConverter();converter.setPrincipalClaimName(config.getIdentity().getUserIdClaim());
        http.csrf(csrf->csrf.disable()).sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth->auth
                        // No payment provider is integrated. The legacy field-only callback must stay closed.
                        .requestMatchers("/payment/callback").denyAll()
                        .requestMatchers("/order/activity/**").hasAuthority("SCOPE_seckill:admin")
                        .requestMatchers("/seckill/**","/refund/**","/order/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth->oauth.jwt(jwt->jwt.decoder(seckillJwtDecoder).jwtAuthenticationConverter(converter)));
        return http.build();
    }
}
