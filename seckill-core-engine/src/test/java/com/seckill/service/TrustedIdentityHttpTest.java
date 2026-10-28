package com.seckill.service;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import com.seckill.config.*;
import com.seckill.controller.*;
import com.seckill.dto.*;
import com.seckill.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TrustedIdentityHttpTest {
    static final KeyPair KEYS=keys();
    private AnnotationConfigWebApplicationContext context;
    private MockMvc http;
    static KeyPair keys(){try{var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);return generator.generateKeyPair();}catch(Exception e){throw new IllegalStateException(e);}}
    @BeforeEach void application(){
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.register(Beans.class,SeckillIdentitySecurity.class);context.refresh();
        http=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    @AfterEach void close(){context.close();}
    private String token(String issuer,String audience,Instant expiry,String subject,KeyPair keys) throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject).issueTime(Date.from(Instant.now()));
        if(expiry!=null)claims.expirationTime(Date.from(expiry));
        var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),claims.build());jwt.sign(new RSASSASigner((RSAPrivateKey)keys.getPrivate()));return jwt.serialize();
    }
    private String valid() throws Exception{return token("https://identity.test","seckill",Instant.now().plusSeconds(120),"42",KEYS);}
    @Test void unconfiguredTrustNeverAcceptsAToken() throws Exception {
        var decoder=new SeckillIdentitySecurity().seckillJwtDecoder(new SeckillConfig());
        assertThrows(org.springframework.security.oauth2.jwt.BadJwtException.class,()->decoder.decode(valid()));
    }
    @Test void aSpoofedHeaderAndBodyCannotAuthenticate() throws Exception {
        http.perform(post("/seckill/do").header("X-User-Id","42").contentType("application/json")
                .content("{\"userId\":42,\"seckillId\":1,\"productId\":1,\"quantity\":1}")).andExpect(status().isUnauthorized());
        verify(context.getBean(SeckillService.class),never()).doSeckill(any());
    }
    @Test void signedIdentityOverridesTheClientBodyAndHeader() throws Exception {
        http.perform(post("/seckill/do").header("Authorization","Bearer "+valid()).header("X-User-Id","999")
                .contentType("application/json").content("{\"userId\":999,\"seckillId\":1,\"productId\":1,\"quantity\":1}"))
                .andExpect(status().isOk());
        var captured=ArgumentCaptor.forClass(SeckillRequest.class);verify(context.getBean(SeckillService.class)).doSeckill(captured.capture());
        assertEquals(42L,captured.getValue().getUserId());
    }
    @Test void signatureIssuerAudienceExpirationAndNumericIdentityAreRequired() throws Exception {
        for(String bad:List.of(token("wrong","seckill",Instant.now().plusSeconds(120),"42",KEYS),
                token("https://identity.test","wrong",Instant.now().plusSeconds(120),"42",KEYS),
                token("https://identity.test","seckill",Instant.now().minusSeconds(1),"42",KEYS),
                token("https://identity.test","seckill",null,"42",KEYS),
                token("https://identity.test","seckill",Instant.now().plusSeconds(120),"not-a-user",KEYS),
                token("https://identity.test","seckill",Instant.now().plusSeconds(120),"42",keys()))) {
            http.perform(get("/seckill/result").param("seckillId","1").header("Authorization","Bearer "+bad)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(context.getBean(OrderQueryService.class));
    }
    @Test void anotherUsersPathCannotBeQueriedAndAdminQueryCannotBeSmuggledAsAParameter() throws Exception {
        http.perform(get("/order/user/999").header("Authorization","Bearer "+valid())).andExpect(status().isForbidden());
        http.perform(get("/order/query").param("queryType","ACTIVITY_LIST").param("seckillId","1")
                .header("Authorization","Bearer "+valid())).andExpect(status().isForbidden());
        verifyNoInteractions(context.getBean(OrderQueryService.class));
    }
    @Test void orderNumberAndRefundQueriesCarryVerifiedOwnership() throws Exception {
        http.perform(get("/order/a").header("Authorization","Bearer "+valid()).header("X-User-Id","999")).andExpect(status().isOk());
        http.perform(get("/refund/query").param("orderNo","a").header("Authorization","Bearer "+valid())).andExpect(status().isOk());
        var captured=ArgumentCaptor.forClass(OrderQueryRequest.class);verify(context.getBean(OrderQueryService.class),times(2)).query(captured.capture());
        assertTrue(captured.getAllValues().stream().allMatch(request->Long.valueOf(42).equals(request.getUserId())));
    }
    @Test void refundBodyIdentityIsAlsoOverridden() throws Exception {
        http.perform(post("/refund/apply").header("Authorization","Bearer "+valid()).header("X-User-Id","999")
                .contentType("application/json").content("{\"orderNo\":\"a\",\"userId\":999,\"refundAmount\":10,\"refundReason\":\"test\"}"))
                .andExpect(status().isOk());
        var captured=ArgumentCaptor.forClass(RefundRequest.class);verify(context.getBean(RefundService.class)).handleRefund(captured.capture());
        assertEquals(42L,captured.getValue().getUserId());
    }
    @Test void authenticatedClientNeedNotSupplyAnUntrustedUserId() throws Exception {
        http.perform(post("/seckill/do").header("Authorization","Bearer "+valid())
                .contentType("application/json").content("{\"seckillId\":1,\"productId\":1,\"quantity\":1}"))
                .andExpect(status().isOk());
        var captured=ArgumentCaptor.forClass(SeckillRequest.class);verify(context.getBean(SeckillService.class)).doSeckill(captured.capture());
        assertEquals(42L,captured.getValue().getUserId());
    }
    @Test void validAdministrativeScopeCanReadActivityOrders() throws Exception {
        var parsed=SignedJWT.parse(valid());
        var admin=new SignedJWT(parsed.getHeader(),new JWTClaimsSet.Builder(parsed.getJWTClaimsSet()).claim("scope","seckill:admin").build());
        admin.sign(new RSASSASigner((RSAPrivateKey)KEYS.getPrivate()));
        http.perform(get("/order/activity/1").header("Authorization","Bearer "+admin.serialize())).andExpect(status().isOk());
        verify(context.getBean(OrderQueryService.class)).query(argThat(request->"ACTIVITY_LIST".equals(request.getQueryType())));
    }
    @Test void unintegratedPaymentCallbackStaysClosedEvenForAnAuthenticatedUser() throws Exception {
        http.perform(post("/payment/callback").header("Authorization","Bearer "+valid())
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Configuration
    @EnableWebMvc
    static class Beans {
        @Bean SeckillConfig config(){
            var c=new SeckillConfig();c.getIdentity().setIssuer("https://identity.test");c.getIdentity().setAudience("seckill");
            c.getIdentity().setPublicKeyPem("-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");return c;
        }
        @Bean SeckillService seckill(){var service=mock(SeckillService.class);when(service.doSeckill(any())).thenReturn(SeckillResponse.success("a"));return service;}
        @Bean OrderQueryService query(){var service=mock(OrderQueryService.class);when(service.query(any())).thenReturn(OrderQueryResponse.notFound());return service;}
        @Bean RefundService refunds(){var service=mock(RefundService.class);when(service.handleRefund(any())).thenReturn(RefundResponse.fail("test"));return service;}
        @Bean SeckillController seckillController(SeckillService s,OrderQueryService q){return new SeckillController(s,q);}
        @Bean OrderQueryController orderController(OrderQueryService q){return new OrderQueryController(q);}
        @Bean RefundController refundController(RefundService r,OrderQueryService q){return new RefundController(r,q);}
        @Bean GlobalExceptionHandler errors(){return new GlobalExceptionHandler();}
    }
}
