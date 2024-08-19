package github.com.ramoura.application;

import github.com.ramoura.domain.Integration;

import java.util.List;

public interface IntegrationRepository {
    List<Integration> getIntegrationList(String clientId);
}
