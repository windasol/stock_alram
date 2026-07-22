package com.example.dart.common.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextsTest {

    @Test
    void null은_빈문자열() {
        assertEquals("", Texts.ellipsize(null, 10));
    }

    @Test
    void max이하면_원문_그대로() {
        assertEquals("abc", Texts.ellipsize("abc", 3));
        assertEquals("abc", Texts.ellipsize("abc", 10));
    }

    @Test
    void max초과면_앞max자_뒤에_말줄임표() {
        assertEquals("abc…", Texts.ellipsize("abcdef", 3));
    }
}
