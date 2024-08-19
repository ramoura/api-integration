package github.com.ramoura.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import github.com.ramoura.application.IntegrationGateway;
import github.com.ramoura.application.IntegrationRepository;
import github.com.ramoura.domain.Integration;

import java.util.List;

public class GetScoreFromIntegrations {

    private final IntegrationRepository integrationRepository;

    public GetScoreFromIntegrations(IntegrationRepository integrationRepository) {
        this.integrationRepository = integrationRepository;
    }

    public void execute(Input input) {

        List<Integration> integrationList = integrationRepository.getIntegrationList(input.clientId);

        integrationList.forEach(integration -> {
            JsonNode requestIntegration = integration.mapperToRequest(input.data);
            JsonNode result =
                IntegrationGatewayFactory.getGateway(integration.getName()).execute(integration, requestIntegration);
            System.out.println("GetScoreFromIntegrations: Result " + result.toString());
        });


        System.out.println("GetScoreFromIntegrations");
    }

    public record Input(String clientId, JsonNode data) {
    }

    private static class IntegrationGatewayFactory {
        public static IntegrationGateway getGateway(String name) {
            return new IntegrationGateway() {
                @Override
                public JsonNode execute(Integration integration, JsonNode data) {
                    System.out.println("IntegrationGatewayFactory: Execute " + integration.getName());
                    System.out.println("IntegrationGatewayFactory: Data " + data.toString());
                    return JsonNodeFactory.instance.objectNode().put("score", 100);
                }
            };
        }
    }
}
