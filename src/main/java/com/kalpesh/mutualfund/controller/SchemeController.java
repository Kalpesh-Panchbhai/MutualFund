package com.kalpesh.mutualfund.controller;

import com.kalpesh.mutualfund.model.Scheme;
import com.kalpesh.mutualfund.service.AmfiService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Controller
public class SchemeController {

    private final AmfiService amfiService;
    private volatile List<Scheme> cachedSchemes = new CopyOnWriteArrayList<>();

    public SchemeController(AmfiService amfiService) {
        this.amfiService = amfiService;
    }

    @GetMapping("/")
    public String listSchemes(Model model) {
        try {
            List<Scheme> schemes = amfiService.fetchAllSchemes();
            cachedSchemes = new CopyOnWriteArrayList<>(schemes);
            model.addAttribute("schemes", schemes);
            model.addAttribute("totalCount", schemes.size());
        } catch (Exception e) {
            model.addAttribute("error", "Failed to fetch data: " + e.getMessage());
        }
        return "schemes";
    }

    @GetMapping(path = "/api/cagr-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamCagr() {
        SseEmitter emitter = new SseEmitter(600_000L);
        List<Scheme> schemes = cachedSchemes;
        int total = schemes.size();

        if (total == 0) {
            try {
                emitter.send(SseEmitter.event().name("done").data("{\"total\":0,\"fetched\":0}"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        java.util.concurrent.atomic.AtomicInteger fetched = new java.util.concurrent.atomic.AtomicInteger(0);

        amfiService.streamCagr(schemes, (code, cagr) -> {
            int done = fetched.incrementAndGet();
            try {
                String cagrStr = cagr != null ? String.format("%.2f", cagr) : "null";
                emitter.send(SseEmitter.event()
                        .name("cagr")
                        .data("{\"code\":" + code + ",\"cagr\":" + cagrStr + ",\"fetched\":" + done + ",\"total\":" + total + "}"));
            } catch (Exception ignored) {}
        }, () -> {
            try {
                emitter.send(SseEmitter.event().name("done").data("{\"total\":" + total + ",\"fetched\":" + fetched.get() + "}"));
                emitter.complete();
            } catch (Exception ignored) {}
        });

        return emitter;
    }
}
