package github.com.ramoura.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Set;

public class Integration {

    public void setUrl(String url) {
        this.url = url;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    private String name;
    private List<InputField> inputFields;

    private Set<String> outputFields;

    private String url;
    private String method;
    private int timeout; // Timeout em segundos

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setInputFields(List<InputField> inputFields) {
        this.inputFields = inputFields;
    }

    public JsonNode mapperToRequest(JsonNode data) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (InputField inputField : inputFields) {
            JsonNode value = data.at(inputField.getPathFrom());
            setJsonNodeByPath(result, inputField.getPathTo(), value);
        }
        return result;
    }
    private void setJsonNodeByPath(ObjectNode root, String path, JsonNode value) {
        String[] segments = path.split("/");
        ObjectNode currentNode = root;

        for (int i = 1; i < segments.length - 1; i++) {
            String segment = segments[i];
            JsonNode nextNode = currentNode.path(segment);
            if (nextNode.isMissingNode() || !nextNode.isObject()) {
                nextNode = currentNode.putObject(segment);
            }
            currentNode = (ObjectNode) nextNode;
        }
        currentNode.set(segments[segments.length - 1], value);
    }

    public JsonNode filterJson(JsonNode originalJson) {
        ObjectNode filteredJson = JsonNodeFactory.instance.objectNode();

        for (String path : outputFields) {
            JsonNode value = originalJson.at(path);

            if (!value.isMissingNode()) {
                addValueToFilteredJson(filteredJson, path, value);
            }
        }

        return filteredJson;
    }

    private void addValueToFilteredJson(ObjectNode currentNode, String path, JsonNode value) {
        String[] parts = path.split("/");

        for (int i = 1; i < parts.length - 1; i++) {
            String key = parts[i];
            currentNode = currentNode.with(key);
        }

        String lastKey = parts[parts.length - 1];
        currentNode.set(lastKey, value);
    }

    public Mono<JsonNode> callApi(JsonNode requestData) {
        // Mapeia os dados de entrada usando o método `mapperToRequest`
        System.out.println("InputData is: "+requestData);
        JsonNode mappedRequest = mapperToRequest(requestData);
        System.out.println("RequestData is: "+mappedRequest);

        WebClient webClient = WebClient.builder()
            .baseUrl(this.url)
            .build();
        System.out.println("call api: "+url+" Method: "+ method);
        return webClient.method(getHttpMethod(this.method))
            .uri(uriBuilder -> uriBuilder.build())
            .bodyValue(mappedRequest)
            .retrieve()
            .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                clientResponse -> handleHttpError(clientResponse))
            .bodyToMono(JsonNode.class)
            .timeout(Duration.ofSeconds(this.timeout))
            .doOnError(this::handleError)
            .onErrorResume(this::handleErrorResponse);
    }

    private Mono<? extends Throwable> handleHttpError(ClientResponse clientResponse) {
        // Tratamento personalizado para erros HTTP
        return clientResponse.bodyToMono(String.class)
            .flatMap(errorBody -> Mono.error(new RuntimeException("API Error: " + clientResponse.statusCode() + " " + errorBody)));
    }

    private void handleError(Throwable throwable) {
        // Log ou outras ações necessárias ao ocorrer um erro
        System.err.println("Erro na chamada API: " + throwable.getMessage());
    }

    private Mono<JsonNode> handleErrorResponse(Throwable throwable) {
        // Tratamento de fallback ou retorno de resposta padrão
        if (throwable instanceof WebClientResponseException) {
            WebClientResponseException ex = (WebClientResponseException) throwable;
            System.err.println("Erro HTTP: " + ex.getStatusCode() + " - " + ex.getResponseBodyAsString());
        } else {
            System.err.println("Erro na chamada API: " + throwable.getMessage());
        }

        // Retornando uma resposta padrão ou Mono vazio
        return Mono.empty();
    }

    private HttpMethod getHttpMethod(String method) {
        switch (method.toUpperCase()) {
            case "GET":
                return HttpMethod.GET;
            case "POST":
                return HttpMethod.POST;
            case "PUT":
                return HttpMethod.PUT;
            case "DELETE":
                return HttpMethod.DELETE;
            default:
                throw new IllegalArgumentException("Invalid HTTP method: " + method);
        }
    }
}

