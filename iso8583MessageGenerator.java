package stepDefinitions;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static utilities.CreateIsoMessage.*;

public class ISO8583MessageGenerator {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @When("^I update iso file \"([^\"]*)\" validate and send the request$")
    public void i_update_iso_file_validate_and_send_the_request(String requestName, DataTable dt) throws IOException {
        loadConfig("iso_config_extended_flattened.json");

        List<Map<String, String>> rows = dt.asMaps(String.class, String.class);

        for (Map<String, String> row : rows) {
            String jsonPath = row.get("JSONPATH");
            String value = row.get("Value");
            String dataType = row.get("DataType");

            applyBddUpdateExtended(jsonPath, value, dataType);
        }

        // Generate default fields, ensuring Primary Bitmap is correct
        generateDefaultFields();

        // Build ISO message & JSON output
        String isoMessage = buildIsoMessage();
        String jsonOutput = buildJsonMessage();

        String response = sendIsoMessageToParser(isoMessage);
        // Print Outputs
        System.out.println("Generated ISO8583 Message:");
        System.out.println(isoMessage);
        System.out.println("\nGenerated JSON Output:");
        System.out.println(jsonOutput);
        System.out.println("\nParser Response:");
        System.out.println(response);
    }

    @When("^I validate all fields with invalid data$")
    public void validateAllFieldsWithInvalidData() throws IOException {
        loadConfig("iso_config_extended_flattened.json");
        JsonNode config = objectMapper.readTree(Files.readString(Path.of("iso_config_extended_flattened.json")));
        
        // First create and validate base message
        List<Map<String, String>> baseData = new ArrayList<>();
        for (Iterator<String> it = config.fieldNames(); it.hasNext();) {
            String fieldId = it.next();
            JsonNode fieldConfig = config.get(fieldId);
            if (fieldConfig.has("name") && fieldConfig.has("SampleData")) {
                Map<String, String> row = new HashMap<>();
                row.put("JSONPATH", fieldConfig.get("name").asText());
                row.put("Value", fieldConfig.get("SampleData").asText());
                row.put("DataType", fieldConfig.get("type").asText());
                baseData.add(row);
            }
        }

        // Apply base valid data
        for (Map<String, String> row : baseData) {
            applyBddUpdateExtended(row.get("JSONPATH"), row.get("Value"), row.get("DataType"));
        }
        generateDefaultFields();

        // Validate base message works
        String validResponse = sendIsoMessageToParser(buildIsoMessage());
        System.out.println("\nValidating base message...");
        System.out.println(validResponse.contains("Error") ? "✗ Base message invalid" : "✓ Base message valid");

        // Test each field
        for (Map<String, String> row : baseData) {
            validateFieldWithInvalidData(row.get("JSONPATH"));
        }
    }
}