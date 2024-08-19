package github.com.ramoura.application;

import com.fasterxml.jackson.databind.JsonNode;
import github.com.ramoura.domain.Integration;

public interface IntegrationGateway {


    JsonNode execute(Integration integration, JsonNode data);

}
