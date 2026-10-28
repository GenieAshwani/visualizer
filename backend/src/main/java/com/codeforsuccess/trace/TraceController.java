package com.codeforsuccess.trace;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/api")
public class TraceController {
    private final JavaTracer tracer = new JavaTracer();
    private final Semaphore slot = new Semaphore(1);
    @PostMapping("/preview") public StaticPreview.Preview preview(@RequestBody TraceModel.Request request) {
        if (request.code() == null || request.code().length() > 40_000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide source up to 40,000 characters.");
        return new StaticPreview().parse(request.code());
    }
    @GetMapping("/health") public Map<String, String> health() {
        return Map.of("status", "ready", "engine", "Java 21 · JDI");
    }
    @PostMapping("/trace") public TraceModel.Result trace(@RequestBody TraceModel.Request request) {
        if (request.code() == null || request.code().isBlank() || request.code().length() > 40_000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide Java source (maximum 40,000 characters).");
        if (request.mainClass() == null || !request.mainClass().matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide a valid fully qualified main class.");
        if (request.input() != null && request.input().length() > 32_000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Program input is limited to 32,000 characters.");
        if (!slot.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "A program is already running. Try again shortly.");
        try { return tracer.trace(request); }
        finally { slot.release(); }
    }
}
