package stepDefinitions;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.util.*;
import utilities.CreateIsoMessage;
import utilities.CreateIsoMessage.TestResult;

public class ISO8583MessageGenerator {
    private CreateIsoMessage isoMessage;

    public ISO8583MessageGenerator() {
        this.isoMessage = new CreateIsoMessage();
    }

    @When("^I update iso file \"([^\"]*)\" validate and send the request$")
    public void i_update_iso_file_validate_and_send_the_request(String requestName, DataTable dt) throws IOException {
        CreateIsoMessage.loadConfig("iso_config_extended_flattened.json");

        List<Map<String, String>> rows = dt.asMaps(String.class, String.class);

        for (Map<String, String> row : rows) {
            String jsonPath = row.get("JSONPATH");
            String value = row.get("Value");
            String dataType = row.get("DataType");

            CreateIsoMessage.applyBddUpdateExtended(jsonPath, value, dataType);
        }

        // Generate default fields, ensuring Primary Bitmap is correct
        CreateIsoMessage.generateDefaultFields();

        // Build ISO message & JSON output
        String isoMessage = CreateIsoMessage.buildIsoMessage();
        String jsonOutput = CreateIsoMessage.buildJsonMessage();

        CreateIsoMessage.sendIsoMessageToParser(isoMessage);
        // Print Outputs
        System.out.println("Generated ISO8583 Message:");
        System.out.println(isoMessage);
        System.out.println("\nGenerated JSON Output:");
        System.out.println(jsonOutput);
    }

    @When("^I validate all fields with invalid data$")
    public void validateAllFieldsWithInvalidData() throws IOException {
        Map<String, List<TestResult>> results = isoMessage.validateAllFieldsWithInvalidData();
        
        // Print summary of results
        System.out.println("\nValidation Test Summary:");
        System.out.println("------------------------");
        
        int totalTests = 0;
        int passedTests = 0;
        
        for (Map.Entry<String, List<TestResult>> fieldEntry : results.entrySet()) {
            String fieldId = fieldEntry.getKey();
            List<TestResult> fieldResults = fieldEntry.getValue();
            
            int fieldPassedTests = (int) fieldResults.stream().filter(r -> r.passed).count();
            totalTests += fieldResults.size();
            passedTests += fieldPassedTests;
            
            System.out.printf("Field %s: %d/%d tests passed%n", 
                fieldId, fieldPassedTests, fieldResults.size());
            
            // Print failed test details if any
            fieldResults.stream()
                .filter(r -> !r.passed)
                .forEach(r -> System.out.printf("  Failed: %s - %s%n", 
                    r.testCategory, r.errorMessage));
        }
        
        System.out.printf("%nOverall Results: %d/%d tests passed (%.1f%%)%n",
            passedTests, totalTests, (passedTests * 100.0 / totalTests));
    }
}