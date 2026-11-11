package org.apache.camel.kit.test.fixture;

import org.apache.camel.CamelContext;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@CamelSpringBootTest
@SpringBootTest
public class ExistingRoutesTest {

    @Autowired
    private CamelContext camelContext;

    @Test
    public void testApiRoutesLoaded() {
        assertNotNull(camelContext.getRoute("get-users-api"));
        assertNotNull(camelContext.getRoute("health-check"));
    }

    @Test
    public void testScheduledTaskLoaded() {
        assertNotNull(camelContext.getRoute("scheduled-task"));
    }

    @Test
    public void testAllRoutesStarted() {
        // Features added later may contribute more routes; the original three must always be present
        assertTrue(camelContext.getRoutes().size() >= 3);
        assertTrue(camelContext.getRouteController().getRouteStatus("get-users-api").isStarted());
        assertTrue(camelContext.getRouteController().getRouteStatus("health-check").isStarted());
        assertTrue(camelContext.getRouteController().getRouteStatus("scheduled-task").isStarted());
    }
}
