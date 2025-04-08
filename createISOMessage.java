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
    private static final String PARSER_URL = "[add url here]"; // Replace with actual URL
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
        String dataSample = getSampleDataFromJsonPath(jsonPath);

        if (fieldNumber == null) {
            System.out.println("Warning: No field found for JSONPath " + jsonPath);
            return;
        }

        JsonNode config = fieldConfig.get(fieldNumber);
        int maxLength = config.has("max_length") ? config.get("max_length").asInt() : config.get("length").asInt();
        String type = config.get("type").asText();

        dataSample = generateCustomValue(dataSample, type);

        // Validate length & type (WARN, not stop execution)
        if (dataSample.length() > maxLength) {
            System.out.println("Warning: Value- "+dataSample+"  for field " + fieldNumber + " exceeds max length " + maxLength + " (Truncated)");
            dataSample = dataSample.substring(0, maxLength);
        }
        if (!type.equalsIgnoreCase(dataType)) {
            System.out.println("Warning: Data type mismatch for field " + fieldNumber + ". Expected: " + type + ", Provided: " + dataType);
        }

        // Store the manually updated field & add to ISO message
        manuallyUpdatedFields.add(fieldNumber);
        addField(fieldNumber, dataSample);
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
        URL url = new URL(PARSER_URL); // Replace with actual URL
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "text/plain");
        connection.setDoOutput(true);

        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = isoMessage.getBytes("utf-8");
            os.write(input, 0, input.length);
        }

        // handle both success and error response
        int responseCode = connection.getResponseCode();
        StringBuilder response = new StringBuilder();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(responseCode >= 400
                        ? connection.getInputStream()
                        : connection.getInputStream(),
                        "utf-8"))) {

            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
        }

        return response.toString();
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
}