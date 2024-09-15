package github.com.ramoura.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.BiFunction;
import java.util.function.Function;

public class InputField {
    private String name;
    private String type;
    private String pathFrom;
    private String pathTo;
    private String description;
    private Function<JsonNode, JsonNode> defaultValue;

    public InputField(String name, String type, String pathFrom, String pathTo, String description, Function<JsonNode, JsonNode> defaultValue) {
        this.name = name;
        this.type = type;
        this.pathFrom = pathFrom;
        this.pathTo = pathTo;
        this.description = description;
        this.defaultValue = defaultValue;
    }

    public InputField(String pathFrom, String pathTo, String name) {
        this.pathFrom = pathFrom;
        this.pathTo = pathTo;
        this.name = name;
    }

    public InputField(String pathFrom, String pathTo, String name , Function<JsonNode, JsonNode> defaultValue) {
        this.pathFrom = pathFrom;
        this.pathTo = pathTo;
        this.name = name;
        this.defaultValue = defaultValue;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getPathFrom() {
        return pathFrom;
    }

    public String getPathTo() {
        return pathTo;
    }

    public String getDescription() {
        return description;
    }

    public JsonNode getDefaultValue(JsonNode originalJson) {
        return defaultValue.apply(originalJson);
    }

    public boolean hasDefaultValue() {
        return defaultValue != null;
    }
}
