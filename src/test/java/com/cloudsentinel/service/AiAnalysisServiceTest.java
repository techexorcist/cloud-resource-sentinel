package com.cloudsentinel.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class AiAnalysisServiceTest {

    private Method extractJsonMethod;
    private Object serviceInstance;

    @BeforeEach
    void setUp() throws Exception {
        // Use reflection to access the private extractJson method.
        // We need an instance of AiAnalysisService, but its constructor requires Spring beans.
        // Instead, we'll invoke the method via a standalone instance created with reflection.
        // Since extractJson is a private instance method, we need to work around the constructor.
        // Approach: create a subclass-free instance using Unsafe or use the method directly.
        // Simplest: allocate an instance without calling the constructor (test-only).
        var unsafeClass = Class.forName("sun.misc.Unsafe");
        var unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = unsafeField.get(null);
        var allocateMethod = unsafeClass.getMethod("allocateInstance", Class.class);
        serviceInstance = allocateMethod.invoke(unsafe, AiAnalysisService.class);

        extractJsonMethod = AiAnalysisService.class.getDeclaredMethod("extractJson", String.class);
        extractJsonMethod.setAccessible(true);
    }

    private String extractJson(String input) throws Exception {
        return (String) extractJsonMethod.invoke(serviceInstance, input);
    }

    @Test
    void extractJson_rawJson_returnsAsIs() throws Exception {
        String json = "{\"key\": \"value\"}";
        assertEquals(json, extractJson(json));
    }

    @Test
    void extractJson_wrappedInJsonCodeBlock_stripsWrapper() throws Exception {
        String input = "```json\n{\"key\": \"value\"}\n```";
        assertEquals("{\"key\": \"value\"}", extractJson(input));
    }

    @Test
    void extractJson_wrappedInPlainCodeBlock_stripsWrapper() throws Exception {
        String input = "```\n{\"key\": \"value\"}\n```";
        assertEquals("{\"key\": \"value\"}", extractJson(input));
    }

    @Test
    void extractJson_textBeforeJson_extractsJson() throws Exception {
        String input = "Here is the analysis:\n{\"summary\": \"test\"}";
        assertEquals("{\"summary\": \"test\"}", extractJson(input));
    }

    @Test
    void extractJson_textAfterJson_extractsJson() throws Exception {
        String input = "{\"summary\": \"test\"}\nEnd of response.";
        assertEquals("{\"summary\": \"test\"}", extractJson(input));
    }

    @Test
    void extractJson_textBeforeAndAfterJson_extractsJson() throws Exception {
        String input = "Sure, here is the JSON:\n{\"exec\": \"summary\"}\nHope this helps!";
        assertEquals("{\"exec\": \"summary\"}", extractJson(input));
    }

    @Test
    void extractJson_nestedBraces_extractsOuterJson() throws Exception {
        String input = "{\"outer\": {\"inner\": \"value\"}}";
        assertEquals("{\"outer\": {\"inner\": \"value\"}}", extractJson(input));
    }

    @Test
    void extractJson_noJsonContent_returnsInputTrimmed() throws Exception {
        String input = "  No JSON here at all  ";
        assertEquals("No JSON here at all", extractJson(input));
    }

    @Test
    void extractJson_codeBlockWithExtraWhitespace_strips() throws Exception {
        String input = "```json\n  {\"a\": 1}  \n```";
        assertEquals("{\"a\": 1}", extractJson(input));
    }

    @Test
    void extractJson_multipleJsonObjects_extractsFromFirstToLast() throws Exception {
        // extractJson uses indexOf('{') and lastIndexOf('}')
        // so it captures from first { to last }
        String input = "{\"first\": 1} some text {\"second\": 2}";
        String result = extractJson(input);
        assertTrue(result.startsWith("{\"first\""));
        assertTrue(result.endsWith("}"));
    }
}
