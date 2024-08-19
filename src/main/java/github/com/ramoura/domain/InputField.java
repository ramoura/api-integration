package github.com.ramoura.domain;

public class InputField {
    private String name;
    private String type;
    private String pathFrom;
    private String pathTo;
    private String description;
    private String defaultValue;
    private String required;

    public InputField(String name, String type, String pathFrom, String pathTo, String description, String defaultValue, String required) {
        this.name = name;
        this.type = type;
        this.pathFrom = pathFrom;
        this.pathTo = pathTo;
        this.description = description;
        this.defaultValue = defaultValue;
        this.required = required;
    }

    public InputField(String pathFrom, String pathTo, String name) {
        this.pathFrom = pathFrom;
        this.pathTo = pathTo;
        this.name = name;
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

    public String getDefaultValue() {
        return defaultValue;
    }

    public String getRequired() {
        return required;
    }
}
