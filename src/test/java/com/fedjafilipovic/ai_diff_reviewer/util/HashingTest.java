package com.fedjafilipovic.ai_diff_reviewer.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins Hashing.sha256Hex against a reference digest. */
class HashingTest {

    @Test
    void sha256HexMatchesReference() throws Exception {
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        byte[] ref = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : ref) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        assertThat(Hashing.sha256Hex(data)).isEqualTo(sb.toString());
    }

    @Test
    void sha256HexOfStringUsesUtf8() {
        String s = "café";
        assertThat(Hashing.sha256Hex(s)).isEqualTo(Hashing.sha256Hex(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void emptyInputHashesToKnownValue() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        assertThat(Hashing.sha256Hex("")).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void differentInputsProduceDifferentHashes() {
        assertThat(Hashing.sha256Hex("a")).isNotEqualTo(Hashing.sha256Hex("b"));
    }
}
