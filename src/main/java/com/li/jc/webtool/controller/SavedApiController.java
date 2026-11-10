package com.li.jc.webtool.controller;

import com.li.jc.webtool.config.AuthInterceptor;
import com.li.jc.webtool.service.SavedApiService;
import com.li.jc.webtool.service.SavedApiService.ApiRequest;
import com.li.jc.webtool.service.SavedApiService.Folder;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Collections;
import java.util.Map;

/** 个人接口库。账号只取自登录会话，不接受客户端指定。 */
@RestController
@RequestMapping("/api/test/saved-api")
public class SavedApiController {

    private final SavedApiService service;

    public SavedApiController(SavedApiService service) {
        this.service = service;
    }

    @Data
    public static class NameRequest {
        private String name;
        private String parentId;
    }

    @Data
    public static class MoveRequest {
        /** 目标文件夹，空表示移到顶层。 */
        private String folderId;
    }

    @ModelAttribute
    public void noCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    private String user(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object user = session == null ? null : session.getAttribute(AuthInterceptor.SESSION_USER_KEY);
        if (!(user instanceof String) || ((String) user).trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录后管理个人接口");
        }
        return (String) user;
    }

    @GetMapping
    public SavedApiService.Library list(HttpServletRequest request) {
        return service.list(user(request));
    }

    @GetMapping("/requests/{id}")
    public ApiRequest get(HttpServletRequest request, @PathVariable String id) {
        return service.get(user(request), id);
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiRequest create(HttpServletRequest request, @RequestBody ApiRequest body) {
        return service.save(user(request), null, body);
    }

    @PutMapping("/requests/{id}")
    public ApiRequest update(HttpServletRequest request, @PathVariable String id, @RequestBody ApiRequest body) {
        return service.save(user(request), id, body);
    }

    @PostMapping("/requests/{id}/rename")
    public ApiRequest renameRequest(HttpServletRequest request, @PathVariable String id, @RequestBody NameRequest body) {
        return service.renameRequest(user(request), id, body.getName());
    }

    @PostMapping("/requests/{id}/move")
    public ApiRequest moveRequest(HttpServletRequest request, @PathVariable String id, @RequestBody MoveRequest body) {
        return service.moveRequest(user(request), id, body.getFolderId());
    }

    @PostMapping("/requests/{id}/copy")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiRequest copyRequest(HttpServletRequest request, @PathVariable String id) {
        return service.copyRequest(user(request), id);
    }

    @DeleteMapping("/requests/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRequest(HttpServletRequest request, @PathVariable String id) {
        service.deleteRequest(user(request), id);
    }

    @PostMapping("/folders")
    @ResponseStatus(HttpStatus.CREATED)
    public Folder createFolder(HttpServletRequest request, @RequestBody NameRequest body) {
        return service.createFolder(user(request), body.getParentId(), body.getName());
    }

    @PostMapping("/folders/{id}/rename")
    public Folder renameFolder(HttpServletRequest request, @PathVariable String id, @RequestBody NameRequest body) {
        return service.renameFolder(user(request), id, body.getName());
    }

    @PostMapping("/folders/{id}/move")
    public Folder moveFolder(HttpServletRequest request, @PathVariable String id, @RequestBody MoveRequest body) {
        return service.moveFolder(user(request), id, body.getFolderId());
    }

    @DeleteMapping("/folders/{id}")
    public Map<String, Integer> deleteFolder(HttpServletRequest request, @PathVariable String id) {
        return service.deleteFolder(user(request), id);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Map<String, String> statusError(ResponseStatusException e, HttpServletResponse response) {
        response.setStatus(e.getStatus().value());
        return Collections.singletonMap("message", e.getReason());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException e) {
        return Collections.singletonMap("message", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> failed(IllegalStateException e) {
        return Collections.singletonMap("message", e.getMessage());
    }
}
