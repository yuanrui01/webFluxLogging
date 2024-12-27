package org.hypnos.webflux.controller;

import org.hypnos.webflux.dto.ContentDTO;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/openapi/webflux")
public class LocalTestController {

    /**
     * exception
     */
    @GetMapping("/{id}")
    public Mono<String> triggerException(@PathVariable("id") String id) {
        throw new RuntimeException("sfadsa");
//        return Mono.just(id);
    }

    /**
     * get echo
     */
    @GetMapping("/echo")
    public Mono<String> handlePostRequest() {
        return Mono.just("echo");
    }

    /**
     * post echo
     */
    @PostMapping("/echo")
    public Mono<ContentDTO> returnContent(@RequestBody ContentDTO content) {
        return Mono.just(content);
    }
}
