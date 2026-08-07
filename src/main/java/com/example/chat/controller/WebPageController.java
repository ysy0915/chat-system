package com.example.chat.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;

@Controller
@RequestMapping
public class WebPageController {

    @GetMapping({"/", "/chat", "/chat/home", "/chat/graph", "/chat/history", "/chat/admin/**", "/chat/sql", "/chat/media", "/chat/3d", "/chat/personal", "/chat/about", "/chat/debate", "/chat/monitor", "/chat/treehole"})
    public ResponseEntity<Resource> index() throws IOException {
        ClassPathResource index = new ClassPathResource("static/chat/index.html");
        if (!index.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("Expires", "0")
                .body(index);
    }
}
