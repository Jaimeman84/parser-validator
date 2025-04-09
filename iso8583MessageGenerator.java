package stepDefinitions;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import static utilities.CreateIsoMessage.*;

public class ISO8583MessageGenerator {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @When("^I update iso file \"([^\"]*)\" validate and send the request$")
    public void i_update_iso_file_validate_and_send_the_request(String requestName, DataTable dt) throws IOException {
        loadConfig("iso_config_extended_flattened.json");

        List<Map<String, String>> rows = dt.asMaps(String.class, String.class);
        List<TestSummary> allResults = new ArrayList<>();

        // Apply all field values from the DataTable
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

        // Send and validate base message
        String response = sendIsoMessageToParser(isoMessage);
        
        // Print Outputs
        System.out.println("Generated ISO8583 Message:");
        System.out.println(isoMessage);
        System.out.println("\nGenerated JSON Output:");
        System.out.println(jsonOutput);
        System.out.println("\nParser Response:");
        System.out.println(response);
        
        // Validate happy path response
        if (response.contains("Error") || !response.contains("200")) {
            throw new AssertionError("Expected 200 success response but got: " + response);
        }
        System.out.println("✓ Base message validation successful (200 OK)");
        
        // Test negative scenarios for each field using the utility in CreateIsoMessage
        for (Map<String, String> row : rows) {
            TestSummary result = validateFieldWithInvalidData(row.get("JSONPATH"));
            allResults.add(result);
        }

        // Print overall summary
        System.out.println("\n\n============================================");
        System.out.println("           OVERALL TEST SUMMARY              ");
        System.out.println("============================================");
        
        // Print individual field summaries
        for (TestSummary summary : allResults) {
            summary.printSummary("  ");
        }
        
        // Print combined summary
        System.out.println("\n============================================");
        System.out.println("           FINAL TOTALS                     ");
        System.out.println("============================================");
        TestSummary.combine(allResults).printSummary("  ");
    }
}