package com.xpengine;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class XpController {
    private final XpService xp;
    private final LeaderboardService leaderboard;
    private final RebuildService rebuild;

    public XpController(XpService xp, LeaderboardService leaderboard, RebuildService rebuild) {
        this.xp = xp; this.leaderboard = leaderboard; this.rebuild = rebuild;
    }

    @PostMapping("/events")
    public EventResponse event(@Valid @RequestBody EventRequest req) {
        return xp.award(req);   // always 200 for a valid request, including duplicates
    }

    @GetMapping("/leaderboard/top")
    public List<Map<String, Object>> top(@RequestParam(defaultValue = "10") int n) {
        return leaderboard.top(Math.min(n, 100)).stream()
                .map(t -> Map.<String, Object>of("userId", t.getValue(), "xp", t.getScore()))
                .toList();
    }

    @GetMapping("/users/{id}/rank")
    public Map<String, Object> rank(@PathVariable long id) {
        Long r = leaderboard.rank(id);
        return r == null ? Map.of() : Map.of("rank", r);
    }

    @PostMapping("/admin/leaderboard/rebuild")
    public ResponseEntity<Map<String, Integer>> rebuild() {
        return ResponseEntity.ok(Map.of("users", rebuild.rebuild()));
    }
}
