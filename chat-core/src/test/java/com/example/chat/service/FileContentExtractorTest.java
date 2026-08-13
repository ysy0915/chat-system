package com.example.chat.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FileContentExtractorTest {

    @Test
    void shouldInstantiate() {
        FileContentExtractor extractor = new FileContentExtractor();
        assertNotNull(extractor);
    }

    @Test
    void shouldReturnEmptyForNullBytes() {
        FileContentExtractor extractor = new FileContentExtractor();
        assertEquals("", extractor.extract(null, "test.txt"));
    }

    @Test
    void shouldReturnEmptyForEmptyBytes() {
        FileContentExtractor extractor = new FileContentExtractor();
        assertEquals("", extractor.extract(new byte[0], "test.txt"));
    }

    @Test
    void shouldReturnEmptyForNullFileName() {
        FileContentExtractor extractor = new FileContentExtractor();
        assertEquals("", extractor.extract("hello".getBytes(), null));
    }

    @Test
    void shouldReturnEmptyForUnsupportedFileType() {
        FileContentExtractor extractor = new FileContentExtractor();
        assertEquals("", extractor.extract("hello".getBytes(), "test.pdf"));
    }
}
