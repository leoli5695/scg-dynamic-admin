package example.moderation;

import com.leoli.gateway.ai.security.ModerationAdapter;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.ServiceLoader;
import static org.junit.jupiter.api.Assertions.*;

class ExampleVendorAdapterTest {
    @Test void pluginIsDiscoverableAndMapsItsOwnProtocol() throws Exception {
        ModerationAdapter adapter = ServiceLoader.load(ModerationAdapter.class).findFirst().orElseThrow();
        assertEquals("EXAMPLE_VENDOR", adapter.id());
        var request = adapter.prepare(new ModerationAdapter.Context("hello", "REQUEST", URI.create("https://example.test"), Map.of("X-Key", "key"), Map.of("appId", "app")));
        assertTrue(new String(request.body(), StandardCharsets.UTF_8).contains("\"content\":\"hello\""));
        assertEquals("key", request.headers().get("X-Key"));
        assertTrue(adapter.parse(response("{\"code\":0,\"data\":{\"decision\":\"reject\"}}")).flagged());
        assertFalse(adapter.parse(response("{\"code\":0,\"data\":{\"decision\":\"pass\"}}")).flagged());
        assertThrows(Exception.class, () -> adapter.parse(response("{\"code\":0,\"data\":{\"decision\":\"unknown\"}}")));
        assertThrows(Exception.class, () -> adapter.parse(response("{\"code\":500,\"data\":{\"decision\":\"pass\"}}")));
    }
    private ModerationAdapter.Response response(String body) {
        return new ModerationAdapter.Response(200, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }
}
