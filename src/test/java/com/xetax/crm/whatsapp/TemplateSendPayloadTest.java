package com.xetax.crm.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xetax.crm.whatsapp.client.MetaWhatsAppClient;
import com.xetax.crm.whatsapp.config.MetaWhatsAppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What actually goes on the wire to Meta for a template send.
 *
 * <p>Meta reads the body, not our intent, so this test reads the body too: it
 * stands a real HTTP server in front of the client and inspects the JSON it
 * receives. The components of a template must arrive as objects — Meta refuses
 * the message with "template.components.0 … expected: '[object, null]'"
 * otherwise, which is exactly what an image template did.
 */
class TemplateSendPayloadTest {

    private static final String COMPONENTS = """
            [{"type":"header","parameters":[{"type":"image","image":{"link":"https://example.com/promo.jpg"}}]},
             {"type":"body","parameters":[{"type":"text","text":"Vanshu"}]}]
            """;

    private HttpServer server;
    private final BlockingQueue<String> bodies = new ArrayBlockingQueue<>(4);
    private MetaWhatsAppClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                bodies.offer(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] reply = "{\"messages\":[{\"id\":\"wamid.TEST\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        server.start();

        MetaWhatsAppProperties properties = new MetaWhatsAppProperties();
        properties.setGraphBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setGraphApiVersion("v23.0");
        client = new MetaWhatsAppClient(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private JsonNode sentBody() throws Exception {
        String body = bodies.poll(5, TimeUnit.SECONDS);
        assertNotNull(body, "nothing was sent");
        return new ObjectMapper().readTree(body);
    }

    @Test
    void theTemplateComponentsArriveAsObjects() throws Exception {
        var result = client.sendTemplateMessage("PNID", "token", "919896458807",
                "image_template", "en", COMPONENTS);

        assertTrue(result.success(), () -> "send failed: " + result.errorMessage());

        JsonNode components = sentBody().path("template").path("components");
        assertTrue(components.isArray(), "components must be a JSON array, was: " + components);
        assertEquals(2, components.size());
        assertTrue(components.get(0).isObject(), "components.0 must be an object, was: " + components.get(0));
        assertTrue(components.get(1).isObject(), "components.1 must be an object, was: " + components.get(1));
    }

    @Test
    void theHeaderImageSurvivesTheJourney() throws Exception {
        client.sendTemplateMessage("PNID", "token", "919896458807", "image_template", "en", COMPONENTS);

        JsonNode header = sentBody().path("template").path("components").path(0);
        assertEquals("header", header.path("type").asText());
        assertEquals("image", header.path("parameters").path(0).path("type").asText());
        assertEquals("https://example.com/promo.jpg",
                header.path("parameters").path(0).path("image").path("link").asText());
    }

    @Test
    void aTemplateWithNoComponentsSendsNoneAtAll() throws Exception {
        client.sendTemplateMessage("PNID", "token", "919896458807", "hello_world", "en", null);

        JsonNode template = sentBody().path("template");
        assertEquals("hello_world", template.path("name").asText());
        assertEquals("en", template.path("language").path("code").asText());
        assertTrue(template.path("components").isMissingNode());
    }

    @Test
    void brokenComponentsAreRefusedBeforeAnythingIsSent() {
        var result = client.sendTemplateMessage("PNID", "token", "919896458807",
                "image_template", "en", "{not json");

        assertFalse(result.success());
        assertEquals("BAD_COMPONENTS", result.errorCode());
        assertTrue(bodies.isEmpty(), "nothing should have been sent");
    }
}
