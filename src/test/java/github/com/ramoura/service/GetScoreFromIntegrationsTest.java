package github.com.ramoura.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import github.com.ramoura.application.IntegrationRepository;
import github.com.ramoura.domain.InputField;
import github.com.ramoura.domain.Integration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class GetScoreFromIntegrationsTest {

    private IntegrationRepository integrationRepository;
    private GetScoreFromIntegrations getScoreFromIntegrations;

    @BeforeEach
    void setUp() {
        integrationRepository = mock(IntegrationRepository.class);
        getScoreFromIntegrations = new GetScoreFromIntegrations(integrationRepository);
    }

    @Test
    void execute_withValidInput_shouldProcessIntegrations() {
        Integration integration = mock(Integration.class);
        when(integration.getName()).thenReturn("TestIntegration");
        when(integration.mapperToRequest(any(JsonNode.class))).thenReturn(JsonNodeFactory.instance.objectNode());

        when(integrationRepository.getIntegrationList("client1")).thenReturn(List.of(integration));

        JsonNode data = JsonNodeFactory.instance.objectNode().put("key", "value");
        GetScoreFromIntegrations.Input input = new GetScoreFromIntegrations.Input("client1", data);

        getScoreFromIntegrations.execute(input);

        verify(integrationRepository).getIntegrationList("client1");
        verify(integration).mapperToRequest(data);
    }

    @Test
    void execute_withNoIntegrations_shouldNotProcessAnyIntegration() {
        when(integrationRepository.getIntegrationList("client1")).thenReturn(Collections.emptyList());

        JsonNode data = JsonNodeFactory.instance.objectNode().put("key", "value");
        GetScoreFromIntegrations.Input input = new GetScoreFromIntegrations.Input("client1", data);

        getScoreFromIntegrations.execute(input);

        verify(integrationRepository).getIntegrationList("client1");
        verifyNoMoreInteractions(integrationRepository);
    }

    @Test
    void execute_withNullInput_shouldThrowException() {
        assertThrows(NullPointerException.class, () -> getScoreFromIntegrations.execute(null));
    }

    @Test
    void execute_withUserJsonNode_shouldProcessIntegrations() {
        Integration integration = new Integration();
        integration.setName("client1");
        integration.setInputFields(
            List.of(
                new InputField("/user/name","/customer_name", "name"),
                new InputField("/user/email","/1/2/3/4/5/email", "email")));

        when(integrationRepository.getIntegrationList("client1")).thenReturn(List.of(integration));

        // Cria o JsonNode raiz
        ObjectNode data = JsonNodeFactory.instance.objectNode();

        // Cria o nó "user" e adiciona os campos "name" e "email"
        ObjectNode userNode = data.putObject("user");
        userNode.put("name", "John Doe");
        userNode.put("email", "john.doe@example.com");

        GetScoreFromIntegrations.Input input = new GetScoreFromIntegrations.Input("client1", data);

        getScoreFromIntegrations.execute(input);

        verify(integrationRepository).getIntegrationList("client1");
    }
}
