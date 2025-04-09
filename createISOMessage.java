package utilities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.cienvironment.internal.com.eclipsesource.json.Json;
import io.cucumber.datatable.DataTable;
import org.apache.http.util.Asserts;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;

import static utilities.CustomTestData.generateCustomValue;
import static utilities.CustomTestData.generateRandomText;

public class CreateIsoMessage  {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static Map<String, JsonNode> fieldConfig;
    private static Map<Integer, String> isoFields = new TreeMap<>();
    private static boolean[] primaryBitmap = new boolean[64];
    private static boolean[] secondaryBitmap = new boolean[64];
    private static Set<String> manuallyUpdatedFields = new HashSet<>(); // Tracks modified fields
    private static final String PARSER_URL = "enter url here"; // Replace with actual URL
    private static final List<String> TEST_CATEGORIES = List.of(
            "invalid_type_value",
            "invalid_special_chars_value",
            "invalid_length_short_value",
            "invalid_length_long_value",
            "invalid_empty_value",
            "invalid_length_exceed_max_value",
            "invalid_datetime_value",
            "invalid_time_value",
            "invalid_date_value",
            "invalid_binary_chars_value"
            // "invalid_hex_chars_value",
            // "invalid_bitmap_format_value",
            // "invalid_bitmap_length_value"
    );

    public void i_create_iso_message(String requestName, DataTable dt) throws IOException {
        loadConfig("iso_config.json");

        List<Map<String, String>> rows = dt.asMaps(String.class, String.class);
        for (Map<String, String> row : rows) {
            String jsonPath = row.get("JSONPATH");
            String value = row.get("Value");
            String dataType = row.get("DataType");

            applyBddUpdate(jsonPath, value, dataType);
        }

        // Generate default fields, ensuring Primary Bitmap is correct
        generateDefaultFields();

        // Build ISO message & JSON output
        String isoMessage = buildIsoMessage();
        String jsonOutput = buildJsonMessage();

        // Print Outputs
        System.out.println("Generated ISO8583 Message:");
        System.out.println(isoMessage);
        System.out.println("\nGenerated JSON Output:");
        System.out.println(jsonOutput);

    }

    public static void loadConfig(String filename) throws IOException {

        String filepath = System.getProperty("user.dir");
        Path pathName;

        if(System.getProperty("os.name").startsWith("Windows")) {
            if(filename.contains("/")) {
                filename=filename.split("/")[0]+"\\"+filename.split("/")[1];
            }
            pathName =Path.of(filepath + "\\src\\test\\resources\\" + filename);
        }
        else {
            if(filename.contains("/")) {
                filename=filename.split("/")[0]+"/"+filename.split("/")[1];
            }
            pathName = Path.of(filepath + "/src/test/resources/" + filename);
        }


        String s=Files.readString(pathName);
        JsonNode jsonNode = objectMapper.readTree(s);
        fieldConfig = new HashMap<>();
        for (Iterator<String> it = jsonNode.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            fieldConfig.put(field, jsonNode.get(field));
        }
    }

    public static void generateDefaultFields() {
        // Ensure MTI defaults to "0100" if not manually set by the user

        if (!isoFields.containsKey(0) && !manuallyUpdatedFields.contains("MTI")) {

            isoFields.put(0, "0100");
        }

        for (String field : fieldConfig.keySet()) {
            JsonNode config = fieldConfig.get(field);
            boolean active = config.get("active").asBoolean();

            if (active && !manuallyUpdatedFields.contains(field)) {
                if(!field.contains("MTI")) {
                    addField(field, generateRandomValue(config));
                }
            }
        }
    }


    public static void applyBddUpdate(String jsonPath, String value, String dataType) {
        String fieldNumber = getFieldNumberFromJsonPath(jsonPath);
        if (fieldNumber == null) {
            System.out.println("Warning: No field found for JSONPath " + jsonPath);
            return;
        }

        JsonNode config = fieldConfig.get(fieldNumber);
        int maxLength = config.has("max_length") ? config.get("max_length").asInt() : config.get("length").asInt();
        String type = config.get("type").asText();

        value = generateCustomValue(value, type);

        // Validate length & type (WARN, not stop execution)
        if (value.length() > maxLength) {
            System.out.println("Warning: Value- "+value+"  for field " + fieldNumber + " exceeds max length " + maxLength + " (Truncated)");
            value = value.substring(0, maxLength);
        }
        if (!type.equalsIgnoreCase(dataType)) {
            System.out.println("Warning: Data type mismatch for field " + fieldNumber + ". Expected: " + type + ", Provided: " + dataType);
        }

        // Store the manually updated field & add to ISO message
        manuallyUpdatedFields.add(fieldNumber);
        addField(fieldNumber, value);
    }

    public static void applyBddUpdateExtended(String jsonPath, String value, String dataType) {
        String fieldNumber = getFieldNumberFromJsonPath(jsonPath);
        if (fieldNumber == null) {
            System.out.println("Warning: No field found for JSONPath " + jsonPath);
            return;
        }

        JsonNode config = fieldConfig.get(fieldNumber);
        int maxLength = config.has("max_length") ? config.get("max_length").asInt() : config.get("length").asInt();
        String type = config.get("type").asText();

        // Apply the value directly instead of getting sample data
        String valueToApply = generateCustomValue(value, type);

        // Validate length & type (WARN, not stop execution)
        if (valueToApply.length() > maxLength) {
            System.out.println("Warning: Value- "+valueToApply+"  for field " + fieldNumber + " exceeds max length " + maxLength + " (Truncated)");
            valueToApply = valueToApply.substring(0, maxLength);
        }
        if (!type.equalsIgnoreCase(dataType)) {
            System.out.println("Warning: Data type mismatch for field " + fieldNumber + ". Expected: " + type + ", Provided: " + dataType);
        }

        // Store the manually updated field & add to ISO message
        manuallyUpdatedFields.add(fieldNumber);
        addField(fieldNumber, valueToApply);
    }

    private static void addField(String field, String dataSample) {
        // Handle MTI separately as a string
        if (field.equalsIgnoreCase("MTI")) {
            isoFields.put(0, dataSample);
            return;
        }

        // Handle Primary Bitmap separately
        if (field.equalsIgnoreCase("PrimaryBitmap") || field.equalsIgnoreCase("SecondaryBitmap")) {
            return; // Bitmaps are automatically generated, do not parse as numeric
        }

        // Convert field number to integer, handling errors
        int fieldNumber;
        try {
            fieldNumber = Integer.parseInt(field);
        } catch (NumberFormatException e) {
            System.out.println("Warning: Invalid field number encountered: " + field);
            return;
        }

        // Store field value and update bitmap
        isoFields.put(fieldNumber, dataSample);
        if (fieldNumber <= 64) {
            primaryBitmap[fieldNumber - 1] = true;
        } else {
            secondaryBitmap[fieldNumber - 65] = true;
            primaryBitmap[0] = true; // Ensure secondary bitmap is marked active
        }
    }

    private static String generateRandomValue(JsonNode config) {
        String type = config.get("type").asText();
        int maxLength = config.has("max_length") ? config.get("max_length").asInt() : config.get("length").asInt();
        return generateRandomText(type, maxLength);
    }

    public static String buildIsoMessage() {
        StringBuilder message = new StringBuilder();

        // Ensure MTI is included, default to "0100" if not manually set
        if (!isoFields.containsKey(0)) {

            message.append("0100");
        } else {
            System.out.println(isoFields.get(0));

            message.append(isoFields.get(0));
        }

        // Ensure bitmap is only generated if at least one field is present in DE 1-64
        boolean hasPrimaryFields = hasActivePrimaryFields();
        if (hasPrimaryFields) {
            message.append(bitmapToHex(primaryBitmap));
        }

        // Only include Secondary Bitmap if DE 65-128 are present
        if (hasActiveSecondaryFields()) {
            message.append(bitmapToHex(secondaryBitmap));
        }

        // Append each field value
        for (int field : isoFields.keySet()) {
            JsonNode config = fieldConfig.get(String.valueOf(field));
            if (config == null) continue;

            // LLVAR and LLLVAR handling
            if ("llvar".equals(config.get("format").asText())) {
                message.append(String.format("%02d", isoFields.get(field).length()));
            } else if ("lllvar".equals(config.get("format").asText())) {
                message.append(String.format("%03d", isoFields.get(field).length()));
            }
            message.append(isoFields.get(field));
        }
        return message.toString();
    }

    private static boolean hasActiveSecondaryFields() {
        for (int i = 0; i < 64; i++) {
            if (secondaryBitmap[i] && isoFields.containsKey(i + 65)) {  // Check fields 65-128
                return true; // Secondary bitmap is required
            }
        }
        return false; // No active fields in DE 65-128
    }

    public static String buildJsonMessage() throws IOException {
        Map<String, Object> outputJson = new HashMap<>();

        // Ensure MTI is correctly stored and printed
        if (!isoFields.containsKey(0) && !manuallyUpdatedFields.contains("MTI")) {

            outputJson.put("MTI", isoFields.getOrDefault(0, "0100"));
        }
        else{
            System.out.println(isoFields.get(0));
            outputJson.put("MTI", isoFields.get(0));
        }

        // Print Primary Bitmap only if active
        if (hasActivePrimaryFields()) {
            outputJson.put("PrimaryBitmap", bitmapToHex(primaryBitmap));
        }

        // Print Secondary Bitmap only if required
        if (hasActiveSecondaryFields()) {
            outputJson.put("SecondaryBitmap", bitmapToHex(secondaryBitmap));
        }
        // Loop through all fields except MTI (Field_0)
        for (int field : isoFields.keySet()) {
            if (field == 0) continue; // Skip MTI from being printed as Field_0

            JsonNode config = fieldConfig.get(String.valueOf(field));
            if (config == null) continue;

            String value = isoFields.get(field);
            String formattedValue = value;

            // Append LLVAR/LLLVAR length values before the actual data
            if ("llvar".equals(config.get("format").asText())) {
                formattedValue = String.format("%02d", value.length()) + value;
            } else if ("lllvar".equals(config.get("format").asText())) {
                formattedValue = String.format("%03d", value.length()) + value;
            }


            // Store correctly formatted field value in JSON output
            outputJson.put("Field_" + field, formattedValue);
        }

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(outputJson);
    }

    public static String getFieldNumberFromJsonPath(String jsonPath) {

        return fieldConfig.entrySet().stream()
                .filter(entry -> {
                    JsonNode nameNode = entry.getValue().get("name");
                    return nameNode != null && jsonPath.equals(nameNode.asText());
                })
                .findFirst()
                .map(entry -> {
                    System.out.println("Match found - Key: " + entry.getKey() + ", JSONPath: " + jsonPath);
                    return entry.getKey();
                })
                .orElse(null);
    }

    public static String getSampleDataFromJsonPath(String jsonPath) {

        return fieldConfig.entrySet().stream()
                .filter(entry -> {
                    JsonNode nameNode = entry.getValue().get("name");
                    return nameNode != null && jsonPath.equals(nameNode.asText());
                })
                .findFirst()
                .map(entry -> {
                    JsonNode node = entry.getValue();

                    String validExample = null;

                    String format = node.get("format").asText();

                    if (format.contains("fixed")) {
                        validExample = node.get("SampleData").asText();
                    } else if (format.contains("llvar") || format.contains("lllvar")) {
                        validExample = node.get("SampleData").asText();
                    }

                    if (validExample !=null) {
                        System.out.println("validExample " + validExample);
                        return validExample;
                    }

                    return null;

                })
                .orElse(null);
    }

    private static String bitmapToHex(boolean[] bitmap) {
        StringBuilder binary = new StringBuilder();
        for (boolean bit : bitmap) {
            binary.append(bit ? "1" : "0");
        }

        // Convert binary string to hex
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 64; i += 4) {
            hex.append(Integer.toHexString(Integer.parseInt(binary.substring(i, i + 4), 2)).toUpperCase());
        }

        return hex.toString();
    }

    private static boolean hasActivePrimaryFields() {
        for (int i = 0; i < 64; i++) {
            if (primaryBitmap[i] && isoFields.containsKey(i + 1)) { // Check fields 1-64
                return true;
            }
        }
        return false;
    }

    /**
     * Sends an ISO8583 message to the parser service
     * @param isoMessage The ISO8583 message to send
     * @return The JSON response from the parser
     */
    public static String sendIsoMessageToParser(String isoMessage) throws IOException {
        URL url = new URL(PARSER_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "text/plain");
        connection.setDoOutput(true);

        // Send the request
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = isoMessage.getBytes("utf-8");
            os.write(input, 0, input.length);
        }

        // Get response code
        int responseCode = connection.getResponseCode();
        StringBuilder response = new StringBuilder();

        // Use error stream for 400 responses, input stream for successful responses
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(responseCode == 400 
                        ? connection.getErrorStream()
                        : connection.getInputStream(), "utf-8"))) {
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
        }

        // For 400 responses, try to parse the error message
        if (responseCode == 400) {
            try {
                JsonNode errorNode = objectMapper.readTree(response.toString());
                if (errorNode.has("message")) {
                    return "Error: " + errorNode.get("message").asText();
                } else if (errorNode.has("error")) {
                    return "Error: " + errorNode.get("error").asText();
                }
            } catch (Exception e) {
                // If can't parse as JSON, return raw response with Error prefix
                return "Error: " + response.toString();
            }
        }

        return response.toString();
    }

    /**
     * Validates if a response contains an error and extracts the error message
     * @param response The response from the parser
     * @return true if the response contains an error, false otherwise
     */
    private static boolean isErrorResponse(String response) {
        if (response.startsWith("Error:")) {
            return true;
        }
        
        try {
            JsonNode responseNode = objectMapper.readTree(response);
            return responseNode.has("error") || responseNode.has("message");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extracts error message from response
     * @param response The response from the parser
     * @return The error message or null if no error
     */
    private static String getErrorMessage(String response) {
        if (response.startsWith("Error:")) {
            return response.substring("Error:".length()).trim();
        }
        
        try {
            JsonNode responseNode = objectMapper.readTree(response);
            if (responseNode.has("message")) {
                return responseNode.get("message").asText();
            } else if (responseNode.has("error")) {
                return responseNode.get("error").asText();
            }
        } catch (Exception e) {
            // If can't parse as JSON, return null
        }
        return null;
    }

    /**
     * Validates all fields in the ISO message with invalid data test cases
     * @return A map containing test results for each field
     * @throws IOException if there's an error reading config or sending messages
     */
    public Map<String, List<TestResult>> validateAllFieldsWithInvalidData() throws IOException {
        Map<String, List<TestResult>> testResults = new HashMap<>();
        
        // First validate the base valid message works
        generateDefaultFields();
        String validIsoMessage = buildIsoMessage();
        String validResponse = sendIsoMessageToParser(validIsoMessage);
        validateSuccessResponse(validResponse);
        System.out.println("Base valid message test passed successfully");

        // Iterate through each field in the config
        for (String fieldId : fieldConfig.keySet()) {
            JsonNode fieldConfig = this.fieldConfig.get(fieldId);
            String fieldName = fieldConfig.get("name").asText();
            List<TestResult> fieldResults = new ArrayList<>();
            testResults.put(fieldId, fieldResults);
            
            System.out.println("\nTesting field " + fieldId + " (" + fieldName + ")");

            // Test each invalid case for the field
            for (String testCategory : TEST_CATEGORIES) {
                if (fieldConfig.has(testCategory)) {
                    TestResult result = testInvalidCase(fieldId, fieldName, fieldConfig, testCategory);
                    fieldResults.add(result);
                    
                    if (result.passed) {
                        System.out.println("  ✓ " + testCategory + " test passed");
                    } else {
                        System.out.println("  ✗ " + testCategory + " test failed: " + result.errorMessage);
                    }
                }
            }
        }
        
        System.out.println("\nAll field validation tests completed");
        return testResults;
    }

    private TestResult testInvalidCase(String fieldId, String fieldName, JsonNode fieldConfig, String testCategory) throws IOException {
        TestResult result = new TestResult(fieldId, testCategory);
        
        try {
            String invalidValue = fieldConfig.get(testCategory).asText();
            String description = fieldConfig.has(testCategory + "_description") ? 
                fieldConfig.get(testCategory + "_description").asText() : testCategory;

            // Store original value
            String originalValue = null;
            if (fieldId.equals("MTI")) {
                originalValue = isoFields.get(0);
                applyBddUpdateExtended("Message Type Indicator", invalidValue, fieldConfig.get("type").asText());
            } else {
                originalValue = isoFields.get(Integer.parseInt(fieldId));
                applyBddUpdateExtended(fieldName, invalidValue, fieldConfig.get("type").asText());
            }

            // Send message with invalid data
            String invalidIsoMessage = buildIsoMessage();
            String errorResponse = sendIsoMessageToParser(invalidIsoMessage);
            
            // Validate error response
            if (!errorResponse.contains("Error")) {
                result.passed = false;
                result.errorMessage = "Expected error response but got success";
            }

            // Restore valid value and verify success
            if (fieldId.equals("MTI")) {
                applyBddUpdateExtended("Message Type Indicator", originalValue, fieldConfig.get("type").asText());
            } else {
                applyBddUpdateExtended(fieldName, originalValue, fieldConfig.get("type").asText());
            }
            
            String restoredIsoMessage = buildIsoMessage();
            String restoredResponse = sendIsoMessageToParser(restoredIsoMessage);
            
            // Validate restored success
            if (restoredResponse.contains("Error")) {
                result.passed = false;
                result.errorMessage = "Failed to restore valid state: " + restoredResponse;
            }

            result.passed = true;
            result.description = description;
            
        } catch (Exception e) {
            result.passed = false;
            result.errorMessage = "Test execution error: " + e.getMessage();
        }
        
        return result;
    }

    private void validateSuccessResponse(String response) {
        if (response.contains("Error")) {
            throw new AssertionError("Expected success response but got error: " + response);
        }
    }

    /**
     * Represents the result of a single field validation test
     */
    public static class TestResult {
        public final String fieldId;
        public final String testCategory;
        public boolean passed;
        public String description;
        public String errorMessage;

        public TestResult(String fieldId, String testCategory) {
            this.fieldId = fieldId;
            this.testCategory = testCategory;
            this.passed = false;
            this.description = "";
            this.errorMessage = "";
        }
    }

    /**
     * Resets the ISO message state to prepare for a new message
     */
    public static void resetState() {
        isoFields.clear();
        primaryBitmap = new boolean[64];
        secondaryBitmap = new boolean[64];
        manuallyUpdatedFields.clear();
    }

    /**
     * Gets the current value of a field
     * @param fieldNumber the field number to get
     * @return the current value or null if not set
     */
    public static String getFieldValue(String fieldNumber) {
        try {
            int fieldNum = Integer.parseInt(fieldNumber);
            return isoFields.get(fieldNum);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Gets the field name from the configuration
     * @param fieldNumber the field number to look up
     * @return the field name or null if not found
     */
    public static String getFieldName(String fieldNumber) {
        JsonNode config = fieldConfig.get(fieldNumber);
        return config != null && config.has("name") ? config.get("name").asText() : null;
    }

    /**
     * Gets all configured field numbers
     * @return list of field numbers that are configured
     */
    public static List<String> getConfiguredFields() {
        return new ArrayList<>(fieldConfig.keySet());
    }

    /**
     * Validates a field with all applicable invalid test categories
     * @param jsonPath The name of the field to test
     * @throws IOException if there's an error sending messages
     */
    public static void validateFieldWithInvalidData(String jsonPath) throws IOException {
        // Test summary counters
        int totalTests = 0;
        int passedTests = 0;
        int unexpectedPasses = 0;  // When we expected failure but got success
        int expectedFailures = 0;  // Invalid tests that correctly failed

        String fieldNumber = getFieldNumberFromJsonPath(jsonPath);
        if (fieldNumber == null) {
            System.out.println("Warning: No field found for JSONPath " + jsonPath);
            return;
        }

        JsonNode config = fieldConfig.get(fieldNumber);
        String type = config.get("type").asText();
        
        // Get the current valid value before we start testing
        String validValue = config.get("SampleData").asText();

        System.out.println("\n========================================");
        System.out.println("Testing field " + fieldNumber + " (" + jsonPath + ")");
        System.out.println("Field type: " + type);
        System.out.println("Original valid value: " + validValue);
        System.out.println("========================================");

        // First ensure we have a valid base message
        applyBddUpdateExtended(jsonPath, validValue, type);
        generateDefaultFields();
        String baseMessage = buildIsoMessage();
        String baseResponse = sendIsoMessageToParser(baseMessage);
        System.out.println("\nValidating base message:");
        System.out.println("Base ISO Message: " + baseMessage);
        System.out.println("Base Response: " + baseResponse);
        
        totalTests++; // Count base validation test
        if (isErrorResponse(baseResponse)) {
            String errorMsg = getErrorMessage(baseResponse);
            System.out.println("❌ Base message validation failed: " + errorMsg);
            unexpectedPasses++; // Base should have passed
            
            // Print summary for base validation failure
            System.out.println("\n========== Test Summary ==========");
            System.out.println("Total test scenarios: " + totalTests);
            System.out.println("✓ Passed tests: 0");
            System.out.println("✗ Unexpected passes (should have failed): 0");
            System.out.println("✓ Expected failures (invalid tests): 0");
            System.out.println("✗ Failed tests that should have passed: 1");
            System.out.println("================================");
            return;
        }
        passedTests++; // Base validation passed
        System.out.println("✓ Base message valid, proceeding with invalid tests");

        // Test each invalid category
        for (String testCategory : TEST_CATEGORIES) {
            if (!config.has(testCategory)) continue;

            totalTests += 2; // Count both invalid test and restoration test
            String invalidValue = config.get(testCategory).asText();
            String description = config.has(testCategory + "_description") ? 
                config.get(testCategory + "_description").asText() : testCategory;

            System.out.println("\n-----------------------------------------");
            System.out.println("Testing category: " + testCategory);
            System.out.println("Description: " + description);
            System.out.println("Invalid value to test: " + invalidValue);

            try {
                // Clear previous state
                resetState();
                
                // Apply base valid values first
                applyBddUpdateExtended(jsonPath, validValue, type);
                generateDefaultFields();
                
                // Then override with invalid value
                System.out.println("\nApplying invalid value to field " + fieldNumber);
                applyBddUpdateExtended(jsonPath, invalidValue, type);
                
                // Build and send message with invalid value
                String invalidIsoMessage = buildIsoMessage();
                System.out.println("Sending ISO message with invalid value:");
                System.out.println("ISO Message: " + invalidIsoMessage);
                
                String errorResponse = sendIsoMessageToParser(invalidIsoMessage);
                System.out.println("Parser Response: " + errorResponse);
                
                boolean hasError = isErrorResponse(errorResponse);
                String errorMsg = hasError ? getErrorMessage(errorResponse) : null;
                
                System.out.println("Invalid test result: " + 
                    (hasError ? "✓ Got expected error: " + errorMsg : "✗ Missing expected error"));
                
                if (hasError) {
                    expectedFailures++; // Invalid test correctly failed
                } else {
                    unexpectedPasses++; // Invalid test unexpectedly passed
                    System.out.println("WARNING: Expected error response for invalid value but got success!");
                }

                // Clear state and restore valid value
                System.out.println("\nRestoring valid value: " + validValue);
                resetState();
                applyBddUpdateExtended(jsonPath, validValue, type);
                generateDefaultFields();
                String restoredIsoMessage = buildIsoMessage();
                
                System.out.println("Sending restored ISO message:");
                System.out.println("ISO Message: " + restoredIsoMessage);
                
                String restoredResponse = sendIsoMessageToParser(restoredIsoMessage);
                System.out.println("Parser Response: " + restoredResponse);
                
                boolean restoredSuccessfully = !isErrorResponse(restoredResponse);
                System.out.println("Restore test result: " + 
                    (restoredSuccessfully ? "✓ Successfully restored" : "✗ Failed to restore"));

                if (restoredSuccessfully) {
                    passedTests++; // Restoration test passed
                } else {
                    String restoreErrorMsg = getErrorMessage(restoredResponse);
                    System.out.println("WARNING: Failed to restore to valid state: " + restoreErrorMsg);
                }

            } catch (Exception e) {
                System.out.println("\n✗ Test failed with exception:");
                e.printStackTrace();
                // Restore valid value even if test fails
                System.out.println("\nAttempting to restore valid value after error...");
                resetState();
                applyBddUpdateExtended(jsonPath, validValue, type);
                generateDefaultFields();
            }
            System.out.println("-----------------------------------------");
        }

        // Print test summary
        System.out.println("\n========== Test Summary ==========");
        System.out.println("Total test scenarios: " + totalTests);
        System.out.println("✓ Passed tests: " + passedTests);
        System.out.println("✗ Unexpected passes (should have failed): " + unexpectedPasses);
        System.out.println("✓ Expected failures (invalid tests): " + expectedFailures);
        System.out.println("✗ Failed tests that should have passed: " + (totalTests - passedTests - expectedFailures - unexpectedPasses));
        System.out.println("================================\n");
    }
}