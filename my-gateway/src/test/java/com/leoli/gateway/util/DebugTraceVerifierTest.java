package com.leoli.gateway.util;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;

class DebugTraceVerifierTest {
    private String sign(long timestamp, String instance, String method, String path) throws Exception {
        String nonce = "0123456789abcdef0123456789abcdef";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-shared-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return timestamp + ":" + nonce + ":" + HexFormat.of().formatHex(mac.doFinal(
                (instance+"\n"+method+"\n"+path+"\n"+timestamp+"\n"+nonce).getBytes(StandardCharsets.UTF_8)));
    }
    @Test void permitsOnlyFreshCorrectlyScopedAdminRequests() throws Exception {
        var verifier = new DebugTraceVerifier("test-shared-secret","gw-a");
        String signature = sign(System.currentTimeMillis(),"gw-a","POST","/ai?q=1");
        assertTrue(verifier.verify(signature,"POST",URI.create("http://gateway/ai?q=1")));
        assertFalse(verifier.verify(signature,"GET",URI.create("http://gateway/ai?q=1")));
        assertFalse(verifier.verify(signature,"POST",URI.create("http://gateway/other?q=1")));
        assertFalse(new DebugTraceVerifier("test-shared-secret","gw-b").verify(signature,"POST",URI.create("http://gateway/ai?q=1")));
        assertFalse(verifier.verify(sign(System.currentTimeMillis()-120000,"gw-a","POST","/ai"),"POST",URI.create("http://gateway/ai")));
        assertFalse(verifier.verify("true","POST",URI.create("http://gateway/ai")));
        assertFalse(new DebugTraceVerifier("","gw-a").verify(signature,"POST",URI.create("http://gateway/ai?q=1")));
    }
}
