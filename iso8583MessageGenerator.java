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
        // Load configuration
        loadConfig("iso_config_extended_flattened.json");
        JsonNode config = objectMapper.readTree(Files.readString(Path.of("iso_config_extended_flattened.json")));
        
        // First validate the base valid message works
        generateDefaultFields();
        String validIsoMessage = buildIsoMessage();
        String validResponse = sendIsoMessageToParser(validIsoMessage);
        if (validResponse.contains("Error")) {
            throw new AssertionError("Base valid message failed: " + validResponse);
        }
        System.out.println("Base valid message test passed successfully");

        // Test each field with invalid data
        int totalTests = 0;
        int passedTests = 0;

        // Iterate through fields in config
        for (Iterator<String> it = config.fieldNames(); it.hasNext();) {
            String fieldId = it.next();
            JsonNode fieldConfig = config.get(fieldId);
            
            if (!fieldConfig.has("name")) continue;
            
            String fieldName = fieldConfig.get("name").asText();
            String dataType = fieldConfig.get("type").asText();
            
            System.out.println("\nTesting field " + fieldId + " (" + fieldName + ")");

            // Test each invalid case
            for (String testCategory : TEST_CATEGORIES) {
                if (fieldConfig.has(testCategory)) {
                    totalTests++;
                    String invalidValue = fieldConfig.get(testCategory).asText();
                    String description = fieldConfig.has(testCategory + "_description") ? 
                        fieldConfig.get(testCategory + "_description").asText() : testCategory;

                    System.out.println("  Testing " + testCategory + ": " + description);

                    try {
                        // Create DataTable row for this test
                        List<Map<String, String>> testRows = new ArrayList<>();
                        Map<String, String> row = new HashMap<>();
                        row.put("JSONPATH", fieldName);
                        row.put("Value", invalidValue);
                        row.put("DataType", dataType);
                        testRows.add(row);
                        
                        // Apply invalid value using the same method as the update function
                        applyBddUpdateExtended(fieldName, invalidValue, dataType);
                        generateDefaultFields();
                        
                        String invalidIsoMessage = buildIsoMessage();
                        String errorResponse = sendIsoMessageToParser(invalidIsoMessage);

                        // Verify error response
                        if (!errorResponse.contains("Error")) {
                            System.out.println("  ✗ Test failed: Expected error response but got success");
                            continue;
                        }

                        // Restore valid state by regenerating the message
                        generateDefaultFields();
                        String restoredIsoMessage = buildIsoMessage();
                        String restoredResponse = sendIsoMessageToParser(restoredIsoMessage);

                        // Verify restored success
                        if (restoredResponse.contains("Error")) {
                            System.out.println("  ✗ Test failed: Could not restore valid state");
                            continue;
                        }

                        passedTests++;
                        System.out.println("  ✓ Test passed");

                    } catch (Exception e) {
                        System.out.println("  ✗ Test failed: " + e.getMessage());
                    }
                }
            }
        }

        // Print summary
        System.out.println("\nValidation Test Summary:");
        System.out.println("------------------------");
        System.out.printf("Total Tests: %d%n", totalTests);
        System.out.printf("Passed Tests: %d%n", passedTests);
        System.out.printf("Success Rate: %.1f%%%n", (passedTests * 100.0 / totalTests));
    }
}