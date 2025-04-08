package utilities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IsoMessageParserTest {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private JsonNode validMessageData;
    private JsonNode isoConfig;
    private CreateIsoMessage isoMessageCreator;

    @BeforeAll
    void setup() throws IOException {
        // Load the valid message data
        String validMessageJson = Files.readString(Path.of("messageResponse.json"));
        validMessageData = objectMapper.readTree(validMessageJson);

        // Load the ISO config
        String configJson = Files.readString(Path.of("iso_config_extended_flattened.json"));
        isoConfig = objectMapper.readTree(configJson);

        isoMessageCreator = new CreateIsoMessage();
    }

    @Test
    void testValidMessage() throws IOException {
        // Test with all valid data
        String isoMessage = isoMessageCreator.buildIsoMessage();
        String response = CreateIsoMessage.sendIsoMessageToParser(isoMessage);
        
        // Verify successful parsing
        assert !response.contains("Error") : "Valid message should parse successfully";
    }

    Stream<TestCase> invalidDataTestCases() {
        List<TestCase> testCases = new ArrayList<>();
        
        // Generate test cases for each field
        for (JsonNode field : validMessageData) {
            String dataElementId = field.get("dataElementId").asText();
            JsonNode fieldConfig = isoConfig.get(dataElementId);
            
            if (fieldConfig == null) continue;

            // Test invalid type
            if (fieldConfig.has("invalid_type_value")) {
                testCases.add(new TestCase(
                    dataElementId,
                    fieldConfig.get("invalid_type_value").asText(),
                    "invalid_type",
                    fieldConfig.get("invalid_type_description").asText()
                ));
            }

            // Test invalid special characters
            if (fieldConfig.has("invalid_special_chars_value")) {
                testCases.add(new TestCase(
                    dataElementId,
                    fieldConfig.get("invalid_special_chars_value").asText(),
                    "invalid_special_chars",
                    fieldConfig.get("invalid_special_chars_description").asText()
                ));
            }

            // Test invalid length (short)
            if (fieldConfig.has("invalid_length_short_value")) {
                testCases.add(new TestCase(
                    dataElementId,
                    fieldConfig.get("invalid_length_short_value").asText(),
                    "invalid_length_short",
                    fieldConfig.get("invalid_length_short_description").asText()
                ));
            }

            // Test invalid length (long)
            if (fieldConfig.has("invalid_length_long_value")) {
                testCases.add(new TestCase(
                    dataElementId,
                    fieldConfig.get("invalid_length_long_value").asText(),
                    "invalid_length_long",
                    fieldConfig.get("invalid_length_long_description").asText()
                ));
            }

            // Add other invalid test cases based on field type
            if (fieldConfig.get("type").asText().equals("binary")) {
                if (fieldConfig.has("invalid_binary_chars_value")) {
                    testCases.add(new TestCase(
                        dataElementId,
                        fieldConfig.get("invalid_binary_chars_value").asText(),
                        "invalid_binary_chars",
                        fieldConfig.get("invalid_binary_chars_description").asText()
                    ));
                }
            }
        }
        
        return testCases.stream();
    }

    @ParameterizedTest
    @MethodSource("invalidDataTestCases")
    void testInvalidData(TestCase testCase) throws IOException {
        // Store original value
        String originalValue = null;
        for (JsonNode field : validMessageData) {
            if (field.get("dataElementId").asText().equals(testCase.dataElementId)) {
                originalValue = field.get("value").asText();
                ((ObjectNode) field).put("value", testCase.invalidValue);
                break;
            }
        }

        try {
            // Generate ISO message with invalid data
            String isoMessage = isoMessageCreator.buildIsoMessage();
            String response = CreateIsoMessage.sendIsoMessageToParser(isoMessage);

            // Verify error response
            assert response.contains("Error") : 
                String.format("Field %s with %s (%s) should cause validation error", 
                    testCase.dataElementId, testCase.testType, testCase.description);

        } finally {
            // Restore original value
            for (JsonNode field : validMessageData) {
                if (field.get("dataElementId").asText().equals(testCase.dataElementId)) {
                    ((ObjectNode) field).put("value", originalValue);
                    break;
                }
            }
        }
    }

    private static class TestCase {
        final String dataElementId;
        final String invalidValue;
        final String testType;
        final String description;

        TestCase(String dataElementId, String invalidValue, String testType, String description) {
            this.dataElementId = dataElementId;
            this.invalidValue = invalidValue;
            this.testType = testType;
            this.description = description;
        }

        @Override
        public String toString() {
            return String.format("Field %s - %s test", dataElementId, testType);
        }
    }
} 