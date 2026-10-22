package com.li.jc.webtool.controller;

import com.li.jc.webtool.config.AuthInterceptor;
import com.li.jc.webtool.service.SavedSqlService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.*;

@RestController
@RequestMapping("/api/database/saved-sql")
public class SavedSqlController {
    private final SavedSqlService service;
    public SavedSqlController(SavedSqlService service) { this.service = service; }

    @ModelAttribute
    public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    private String user(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object user = session == null ? null : session.getAttribute(AuthInterceptor.SESSION_USER_KEY);
        if (!(user instanceof String) || ((String) user).trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录后管理个人 SQL 脚本");
        }
        return (String) user;
    }

    @GetMapping
    public List<SavedSqlService.Script> list(HttpServletRequest request) { return service.list(user(request)); }

    @GetMapping("/{id}")
    public SavedSqlService.Script get(HttpServletRequest request, @PathVariable String id) { return service.get(user(request), id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavedSqlService.Script create(HttpServletRequest request, @RequestBody SavedSqlService.Script script) {
        return service.save(user(request), null, script);
    }

    @PutMapping("/{id}")
    public SavedSqlService.Script update(HttpServletRequest request, @PathVariable String id,
                                       @RequestBody SavedSqlService.Script script) {
        return service.save(user(request), id, script);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(HttpServletRequest request, @PathVariable String id) { service.delete(user(request), id); }

    @ExceptionHandler(ResponseStatusException.class)
    public Map<String, String> statusError(ResponseStatusException e, HttpServletResponse response) {
        response.setStatus(e.getStatus().value());
        return Collections.singletonMap("message", e.getReason());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException e) { return Collections.singletonMap("message", e.getMessage()); }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> failed(IllegalStateException e) { return Collections.singletonMap("message", e.getMessage()); }
}
