package com.gxcj.xjtool.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/terminal")
    public String terminal() {
        return "terminal";
    }

    @GetMapping("/api-test")
    public String apiTest() {
        return "api-test";
    }

    @GetMapping("/sql-tool")
    public String sqlTool() {
        return "sql-tool";
    }

    @GetMapping("/audit-dashboard")
    public String auditDashboard() {
        return "audit-dashboard";
    }

    @GetMapping("/presentation")
    public String presentation() {
        return "presentation";
    }
}
