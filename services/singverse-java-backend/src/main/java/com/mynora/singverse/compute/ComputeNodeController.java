package com.mynora.singverse.compute;

import static com.mynora.singverse.compute.ComputeNodeDtos.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/compute-node")
public class ComputeNodeController {
    private final ComputeNodeSettings settings;
    private final ComputeNodeProbe probe;
    public ComputeNodeController(ComputeNodeSettings settings, ComputeNodeProbe probe) {this.settings=settings;this.probe=probe;}
    @GetMapping("") public Settings get() {return settings.view();}
    @PutMapping("") public Settings save(@RequestBody Save input) {return settings.save(input);}
    @PostMapping("/test") public Check test(@RequestBody Save input) {return probe.check(settings.draft(input));}
}
