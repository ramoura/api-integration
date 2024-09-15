package github.com.ramoura.domain;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlaceholderResolver {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(.*?)\\}\\}");

    public static JsonNode resolve(String input, String path, JsonNode originalJson) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();

        Map<String, Function<String, String>> resolvers = Map.of(
            "UUID", p -> java.util.UUID.randomUUID().toString(),
            "YEAR_OF_BIRTH", p -> extractYearFromJson(p, originalJson),
            input, p -> p
        );

        while (matcher.find()) {
            String placeholder = matcher.group(1);
            Function<String, String> resolver = resolvers.get(placeholder);
            if (resolver != null) {
                String replacement = resolver.apply(path);
                matcher.appendReplacement(sb, replacement != null ? replacement : "");
            }
        }
        matcher.appendTail(sb);
        return new TextNode(sb.toString());
    }

    private static String extractYearFromJson(String path, JsonNode originalJson) {
        // Extrai o ano de nascimento a partir do JsonNode original usando o path.
        JsonNode dateNode = originalJson.at(path);
        if (dateNode != null && dateNode.isTextual()) {
            String date = dateNode.asText();
            return date.substring(0, 4); // Assumindo que o formato da data é "yyyy-MM-dd".
        }
        return "";
    }
}
