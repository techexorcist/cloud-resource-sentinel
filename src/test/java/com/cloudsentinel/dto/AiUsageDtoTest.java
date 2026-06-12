package com.cloudsentinel.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiUsageDtoTest {

    @Test
    void empty_returnsAllNullsAndZeros() {
        AiUsageDto dto = AiUsageDto.empty();
        assertNull(dto.provider());
        assertNull(dto.model());
        assertNull(dto.promptTokens());
        assertNull(dto.completionTokens());
        assertNull(dto.totalTokens());
        assertEquals(0, dto.durationMs());
        assertEquals(0, dto.promptCharacters());
        assertEquals(0, dto.responseCharacters());
        assertEquals(0, dto.tokensPerSecond());
        assertNull(dto.funFact());
    }

    @Test
    void of_calculatesTokensPerSecondCorrectly() {
        // 500 completion tokens in 2000ms = 250 tokens/sec
        AiUsageDto dto = AiUsageDto.of("ollama", "llama3", 100, 500, 600, 2000, 1000, 2000);
        assertEquals(250.0, dto.tokensPerSecond());
    }

    @Test
    void of_withZeroDurationReturnsZeroTps() {
        AiUsageDto dto = AiUsageDto.of("ollama", "llama3", 100, 500, 600, 0, 1000, 2000);
        assertEquals(0.0, dto.tokensPerSecond());
    }

    @Test
    void of_withNullCompletionTokensReturnsZeroTps() {
        AiUsageDto dto = AiUsageDto.of("ollama", "llama3", 100, null, 100, 2000, 1000, 2000);
        assertEquals(0.0, dto.tokensPerSecond());
    }

    @Test
    void of_withZeroCompletionTokensReturnsZeroTps() {
        AiUsageDto dto = AiUsageDto.of("ollama", "llama3", 100, 0, 100, 2000, 1000, 2000);
        assertEquals(0.0, dto.tokensPerSecond());
    }

    @Test
    void of_roundsTpsToOneDecimal() {
        // 333 tokens in 1000ms = 333.0 tps -> should be 333.0
        AiUsageDto dto = AiUsageDto.of("ollama", "llama3", 100, 333, 433, 1000, 500, 500);
        assertEquals(333.0, dto.tokensPerSecond());

        // 100 tokens in 3000ms = 33.333... -> should round to 33.3
        AiUsageDto dto2 = AiUsageDto.of("ollama", "llama3", 100, 100, 200, 3000, 500, 500);
        assertEquals(33.3, dto2.tokensPerSecond());
    }

    @Test
    void funFact_blazingFastWhenTpsOver100() {
        // 500 tokens in 1000ms = 500 tps
        AiUsageDto dto = AiUsageDto.of("ollama", "llama3", 100, 500, 600, 1000, 500, 500);
        assertTrue(dto.funFact().contains("Blazing fast"));
    }

    @Test
    void funFact_speedDemonWhenTpsOver50() {
        // 75 tokens in 1000ms = 75 tps
        AiUsageDto dto = AiUsageDto.of("ollama", "llama3", 100, 75, 175, 1000, 500, 500);
        assertTrue(dto.funFact().contains("Speed demon"));
    }

    @Test
    void funFact_steadyAndStrongWhenTpsOver20() {
        // 30 tokens in 1000ms = 30 tps
        AiUsageDto dto = AiUsageDto.of("ollama", "llama3", 100, 30, 130, 1000, 500, 500);
        assertTrue(dto.funFact().contains("Steady and strong"));
    }

    @Test
    void funFact_tookItsTimeWhenTpsAboveZeroButBelow20() {
        // 10 tokens in 1000ms = 10 tps
        AiUsageDto dto = AiUsageDto.of("ollama", "llama3", 100, 10, 110, 1000, 500, 500);
        assertTrue(dto.funFact().contains("took its time"));
    }

    @Test
    void funFact_twoMinuteMeditationWhenDurationOver120s() {
        // tps = 0 (null completion), totalTokens > 0, durationMs > 120_000
        AiUsageDto dto = AiUsageDto.of("ollama", "llama3", 100, null, 100, 130_000, 500, 500);
        // tps is 0 but totalTokens is 100 (not null/zero), so won't hit mysterious ways.
        // tps == 0 so "took its time" branch: tps > 0 is false. Next: durationMs > 120_000
        assertTrue(dto.funFact().contains("2+ minute meditation"));
    }

    @Test
    void funFact_fullMinuteWhenDurationOver60s() {
        // tps = 0, totalTokens > 0, durationMs between 60_001 and 120_000
        AiUsageDto dto = AiUsageDto.of("ollama", "llama3", 100, null, 100, 90_000, 500, 500);
        assertTrue(dto.funFact().contains("full minute"));
    }

    @Test
    void funFact_novelLengthWhenTotalTokensOver3000() {
        // tps = 0, totalTokens > 3000, durationMs <= 60_000
        AiUsageDto dto = AiUsageDto.of("ollama", "llama3", 100, null, 3500, 30_000, 500, 500);
        assertTrue(dto.funFact().contains("novel-length"));
    }

    @Test
    void funFact_heftyPromptWhenPromptCharsOver10000() {
        // tps = 0, totalTokens <= 3000, durationMs <= 60_000, promptChars > 10000
        AiUsageDto dto = AiUsageDto.of("ollama", "llama3", 100, null, 500, 30_000, 15000, 500);
        assertTrue(dto.funFact().contains("hefty prompt"));
    }

    @Test
    void funFact_fallbackAnotherDay() {
        // tps = 0, totalTokens <= 3000, durationMs <= 60_000, promptChars <= 10000
        AiUsageDto dto = AiUsageDto.of("ollama", "llama3", 100, null, 500, 30_000, 500, 500);
        assertTrue(dto.funFact().contains("Another day"));
    }

    @Test
    void funFact_mysteriousWaysWhenTokensNull() {
        AiUsageDto dto = AiUsageDto.of("ollama", "llama3", null, null, null, 5000, 500, 500);
        assertTrue(dto.funFact().contains("mysterious ways"));
    }

    @Test
    void funFact_mysteriousWaysWhenTokensZero() {
        AiUsageDto dto = AiUsageDto.of("ollama", "llama3", 0, null, 0, 5000, 500, 500);
        assertTrue(dto.funFact().contains("mysterious ways"));
    }

    @Test
    void of_setsProviderAndModel() {
        AiUsageDto dto = AiUsageDto.of("bedrock", "claude-3", 10, 20, 30, 1000, 100, 200);
        assertEquals("bedrock", dto.provider());
        assertEquals("claude-3", dto.model());
        assertEquals(10, dto.promptTokens());
        assertEquals(20, dto.completionTokens());
        assertEquals(30, dto.totalTokens());
        assertEquals(1000, dto.durationMs());
        assertEquals(100, dto.promptCharacters());
        assertEquals(200, dto.responseCharacters());
    }
}
