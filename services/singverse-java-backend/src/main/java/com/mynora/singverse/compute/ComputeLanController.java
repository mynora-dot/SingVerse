package com.mynora.singverse.compute;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/compute-node")
public class ComputeLanController {
    private final ComputeLanDiscovery lan;
    public ComputeLanController(ComputeLanDiscovery lan) { this.lan = lan; }
    @PostMapping("/discovery") public ComputeLanDiscovery.Result discover(@RequestBody(required = false) ComputeLanDiscovery.Scan input) { return lan.discover(input); }
    @PostMapping("/pair") public ComputeNodeDtos.Settings pair(@RequestBody ComputeLanDiscovery.Pair input) { return lan.pair(input); }
}
