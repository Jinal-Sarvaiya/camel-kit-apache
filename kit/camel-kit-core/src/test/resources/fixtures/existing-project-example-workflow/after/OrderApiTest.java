package org.apache.camel.kit.test.fixture;

import org.apache.camel.CamelContext;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@CamelSpringBootTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
public class OrderApiTest {

    @Autowired
    private CamelContext camelContext;

    @Test
    public void testOrdersApiRouteLoaded() {
        assertNotNull(camelContext.getRoute("get-orders-api"));
    }

    @Test
    public void testOrdersApiRouteStarted() {
        assertTrue(camelContext.getRouteController().getRouteStatus("get-orders-api").isStarted());
    }

    @Test
    public void testOrdersApiEndpointReturnsJson() throws Exception {
        // Call the REST endpoint using HTTP client
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8080/api/orders"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "HTTP status should be 200");
        String body = response.body();
        assertNotNull(body, "Response body should not be null");
        assertTrue(body.contains("\"id\":101"), "Response should contain order 101");
        assertTrue(body.contains("\"product\":\"Widget\""), "Response should contain Widget product");
        assertTrue(body.contains("\"id\":102"), "Response should contain order 102");
        assertTrue(body.contains("\"product\":\"Gadget\""), "Response should contain Gadget product");
    }

    @Test
    public void testOrdersApiRouteCount() {
        // Should now have 4 routes total (3 original + 1 new)
        assertEquals(4, camelContext.getRoutes().size(), "Should have exactly 4 routes");
    }
}
