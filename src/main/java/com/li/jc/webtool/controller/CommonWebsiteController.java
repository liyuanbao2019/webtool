package com.li.jc.webtool.controller;

import com.li.jc.webtool.service.CommonWebsiteService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/server/common-websites/config")
public class CommonWebsiteController {
    private final CommonWebsiteService websites;

    public CommonWebsiteController(CommonWebsiteService websites) { this.websites = websites; }

    @ModelAttribute
    public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @GetMapping
    public CommonWebsiteService.Settings get() { return websites.settings(); }

    @PutMapping
    public CommonWebsiteService.Settings save(@RequestBody CommonWebsiteService.Settings settings) {
        return websites.save(settings);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException e) {
        return Collections.singletonMap("message", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> saveFailed(IllegalStateException e) {
        return Collections.singletonMap("message", e.getMessage());
    }
}
