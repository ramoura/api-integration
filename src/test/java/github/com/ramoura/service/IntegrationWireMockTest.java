package github.com.ramoura.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import github.com.ramoura.domain.InputField;
import github.com.ramoura.domain.Integration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WireMockTest(httpPort = 9090)
class IntegrationWireMockTest {

    private Integration integration;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        integration = new Integration();
        integration.setUrl("http://localhost:9090/api/test");
        integration.setMethod("POST");
        integration.setTimeout(5);
        objectMapper = new ObjectMapper();
    }

    @Test
    void callApi_successfulResponse() throws Exception {
        integration.setName("client1");
        integration.setInputFields(
            List.of(
                new InputField("/user/name", "/customer_name", "name"),
                new InputField("/user/email", "/customer_email", "email")));

        // Configura a resposta mockada
        stubFor(post(urlEqualTo("/api/test"))
            .willReturn(okJson("{\"status\": \"success\"}")));

        JsonNode requestData = getRequestBody();
        Mono<JsonNode> response = integration.callApi(requestData);

        assertEquals("success", response.block().get("status").asText());
    }

    @Test
    void callApi_errorResponse() throws JsonProcessingException {
        integration.setName("client1");
        integration.setInputFields(
            List.of(
                new InputField("/user/name", "/customer_name", "name"),
                new InputField("/user/email", "/customer_email", "email")));

        // Configura a resposta de erro mockada
        stubFor(post(urlEqualTo("/api/test"))
            .willReturn(serverError().withBody("{\"error\": \"internal_server_error\"}")));

        JsonNode requestData = getRequestBody();
        Mono<JsonNode> response = integration.callApi(requestData);
        assertTrue(response.blockOptional().isEmpty());
    }

    @Test
    void callApi_withEmptyRequestData_shouldReturnEmptyResponse() throws JsonProcessingException {
        integration.setName("client1");
        integration.setInputFields(
            List.of(
                new InputField("/user/name", "/customer_name", "name"),
                new InputField("/user/email", "/customer_email", "email")));

        stubFor(post(urlEqualTo("/api/test"))
            .willReturn(okJson("{}")));

        JsonNode requestData = objectMapper.readTree("{}");
        Mono<JsonNode> response = integration.callApi(requestData);

        assertTrue(response.blockOptional().isEmpty());
    }

    @Test
    void callApi_withInvalidJsonNode_shouldHandleErrorGracefully() throws JsonProcessingException {
        integration.setName("client1");
        integration.setInputFields(
            List.of(
                new InputField("/user/name", "/customer_name", "name"),
                new InputField("/user/email", "/customer_email", "email")));

        stubFor(post(urlEqualTo("/api/test"))
            .willReturn(okJson("{\"status\": \"success\"}")));

        JsonNode requestData = objectMapper.readTree("{invalid_json}");
        Mono<JsonNode> response = integration.callApi(requestData);

        assertTrue(response.blockOptional().isEmpty());
    }

    @Test
    void callApi_withTimeout_shouldHandleTimeoutError() throws JsonProcessingException {
        integration.setName("client1");
        integration.setInputFields(
            List.of(
                new InputField("/user/name", "/customer_name", "name"),
                new InputField("/user/email", "/customer_email", "email")));

        stubFor(post(urlEqualTo("/api/test"))
            .willReturn(okJson("{\"status\": \"success\"}")));

        integration.setTimeout(1); // Set a very short timeout to trigger timeout error

        JsonNode requestData = getRequestBody();
        Mono<JsonNode> response = integration.callApi(requestData);

        assertTrue(response.blockOptional().isEmpty());
    }


    private JsonNode getRequestBody() throws JsonProcessingException {
        return objectMapper.readTree("""
            {
                "user": {
                    "name": "John Doe",
                    "email": "john.doe@email.com",
                    "age": 29
                }
            }
         """);

    }

}
