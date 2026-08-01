package com.example.chat.controller;

import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.ModelConfigRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// @RestController
// @RequestMapping("/api/v1")
public class ModelConfigController {
    private final ModelConfigRepository repo;

    public ModelConfigController(ModelConfigRepository repo) {
        this.repo = repo;
    }

    // // Public: list enabled models for users
    // @GetMapping("/models")
    // public ResponseEntity<List<ModelConfig>> listEnabled() {
    //     return ResponseEntity.ok(repo.findAllEnabled());
    // }

    // // Admin CRUD
    // @GetMapping("/admin/models")
    // public ResponseEntity<List<ModelConfig>> listAll() {
    //     return ResponseEntity.ok(repo.findAll());
    // }
}
