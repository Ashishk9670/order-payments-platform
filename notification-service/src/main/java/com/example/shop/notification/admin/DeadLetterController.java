package com.example.shop.notification.admin;

import com.example.shop.notification.admin.DeadLetterService.DeadLetterRecord;
import com.example.shop.notification.admin.DeadLetterService.DeadLetterTopic;
import com.example.shop.notification.admin.DeadLetterService.ReplayResult;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN only (see SecurityConfig). Runbook: docs/runbooks/dead-letters.md. */
@RestController
@RequestMapping("/api/v1/admin/dlt")
class DeadLetterController {

    static final int MAX_PEEK = 200;
    static final int MAX_REPLAY = 1000;

    private final DeadLetterService service;

    DeadLetterController(DeadLetterService service) {
        this.service = service;
    }

    @GetMapping
    List<DeadLetterTopic> topics() {
        return service.list();
    }

    @GetMapping("/{topic}/records")
    List<DeadLetterRecord> records(@PathVariable String topic, @RequestParam(defaultValue = "20") int limit) {
        return service.peek(known(topic), clamp(limit, MAX_PEEK));
    }

    @PostMapping("/{topic}/replay")
    ReplayResult replay(@PathVariable String topic, @RequestParam(defaultValue = "100") int max) {
        return service.replay(known(topic), clamp(max, MAX_REPLAY));
    }

    @PostMapping("/{topic}/acknowledge")
    Map<String, Object> acknowledge(@PathVariable String topic) {
        return Map.of("topic", topic, "acknowledged", service.acknowledge(known(topic)));
    }

    private static String known(String topic) {
        if (!DeadLetterService.isKnownDeadLetterTopic(topic)) {
            throw new UnknownDeadLetterTopicException(topic);
        }
        return topic;
    }

    private static int clamp(int value, int max) {
        return Math.max(1, Math.min(value, max));
    }

    static class UnknownDeadLetterTopicException extends RuntimeException {
        UnknownDeadLetterTopicException(String topic) {
            super("Not a dead-letter topic of this platform: " + topic);
        }
    }

    @ExceptionHandler(UnknownDeadLetterTopicException.class)
    ProblemDetail unknown(UnknownDeadLetterTopicException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
