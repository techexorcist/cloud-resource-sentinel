package com.cloudsentinel.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiFilteringDtoTest {

    @Test
    void disabled_returnsEnabledFalseAndAllNulls() {
        AiFilteringDto dto = AiFilteringDto.disabled();
        assertFalse(dto.enabled());
        assertNull(dto.provider());
        assertNull(dto.totalCandidates());
        assertNull(dto.trulyIdleCount());
        assertNull(dto.aiModel());
    }

    @Test
    void enabled_returnsEnabledTrueWithProvidedValues() {
        AiFilteringDto dto = AiFilteringDto.enabled("ollama", 50, 12, "llama3");
        assertTrue(dto.enabled());
        assertEquals("ollama", dto.provider());
        assertEquals(50, dto.totalCandidates());
        assertEquals(12, dto.trulyIdleCount());
        assertEquals("llama3", dto.aiModel());
    }

    @Test
    void enabled_withNullModel() {
        AiFilteringDto dto = AiFilteringDto.enabled("bedrock", 30, 8, null);
        assertTrue(dto.enabled());
        assertEquals("bedrock", dto.provider());
        assertEquals(30, dto.totalCandidates());
        assertEquals(8, dto.trulyIdleCount());
        assertNull(dto.aiModel());
    }

    @Test
    void enabled_withZeroCounts() {
        AiFilteringDto dto = AiFilteringDto.enabled("ollama", 0, 0, "llama3");
        assertTrue(dto.enabled());
        assertEquals(0, dto.totalCandidates());
        assertEquals(0, dto.trulyIdleCount());
    }

    @Test
    void recordEquality() {
        AiFilteringDto a = AiFilteringDto.disabled();
        AiFilteringDto b = AiFilteringDto.disabled();
        assertEquals(a, b);

        AiFilteringDto c = AiFilteringDto.enabled("ollama", 10, 5, "llama3");
        AiFilteringDto d = AiFilteringDto.enabled("ollama", 10, 5, "llama3");
        assertEquals(c, d);

        assertNotEquals(a, c);
    }
}
