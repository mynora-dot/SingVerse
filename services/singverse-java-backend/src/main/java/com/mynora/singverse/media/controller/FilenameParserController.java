package com.mynora.singverse.media.controller;

import com.mynora.singverse.media.dto.FilenameParserDtos.*;
import com.mynora.singverse.media.filename.FilenameRulePreview;
import com.mynora.singverse.media.service.FilenameParserConfigService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/filename-parser")
public class FilenameParserController {
    private final FilenameParserConfigService service;
    public FilenameParserController(FilenameParserConfigService service) { this.service = service; }

    @GetMapping("/rules")
    public Configuration rules() { return service.read(); }

    @PutMapping("/rules")
    public Configuration save(@RequestBody Save input) { return service.save(input); }

    @PostMapping("/preview")
    public FilenameRulePreview preview(@RequestBody Preview input) { return service.preview(input); }

    @GetMapping({"/rule-definitions", "/profiles"})
    public Workspace workspace() { return service.workspace(); }

    @PostMapping("/rule-definitions")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Workspace createDefinition(@RequestBody RuleWrite input) { return service.writeDefinition(null, input); }

    @PutMapping("/rule-definitions/{id}")
    public Workspace updateDefinition(@PathVariable String id, @RequestBody RuleWrite input) { return service.writeDefinition(id, input); }

    @DeleteMapping("/rule-definitions/{id}")
    public Workspace deleteDefinition(@PathVariable String id, @RequestParam Long revision) { return service.deleteDefinition(id, revision); }

    @PostMapping("/profiles")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Workspace createProfile(@RequestBody ProfileWrite input) { return service.writeProfile(null, input); }

    @PutMapping("/profiles/{id}")
    public Workspace updateProfile(@PathVariable String id, @RequestBody ProfileWrite input) { return service.writeProfile(id, input); }

    @DeleteMapping("/profiles/{id}")
    public Workspace deleteProfile(@PathVariable String id, @RequestParam Long revision) { return service.deleteProfile(id, revision); }

    @PostMapping("/profiles/preview")
    public ProfilePreview previewProfile(@RequestBody ProfilePreviewInput input) { return service.previewProfile(input); }
}
