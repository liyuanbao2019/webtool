package com.li.jc.webtool.controller;

import com.li.jc.webtool.service.ConnectedScriptService;
import com.li.jc.webtool.service.ScriptImportService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/ssh/scripts")
public class ConnectedScriptController {
    private final ScriptImportService importer;
    private final ConnectedScriptService scripts;

    public ConnectedScriptController(ScriptImportService importer, ConnectedScriptService scripts) {
        this.importer = importer;
        this.scripts = scripts;
    }

    @PostMapping("/import")
    public ScriptImportService.ImportedScript importFile(@RequestParam("file") MultipartFile file) throws Exception {
        if (file.getSize() > 2 * 1024 * 1024) throw new IllegalArgumentException("上传文件不能超过 2MB");
        try (InputStream input = file.getInputStream()) {
            return importer.importFile(file.getOriginalFilename(), input);
        }
    }

    @GetMapping("/presets/{id}")
    public ScriptImportService.ImportedScript preset(@PathVariable String id) throws Exception {
        if (!"baseline".equals(id) && !"weak-password".equals(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "脚本不存在");
        }
        try (InputStream input = new ClassPathResource("scripts/" + id + ".bms").getInputStream()) {
            return importer.importFile(id + ".bms", input);
        }
    }

    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody ConnectedScriptService.RunRequest body, HttpServletRequest request)
            throws Exception {
        return scripts.start(request.getSession().getId(), body);
    }

    @GetMapping("/tasks/{id}")
    public Map<String, Object> status(@PathVariable String id, @RequestParam(defaultValue = "0") int offset,
                                      HttpServletRequest request) {
        return scripts.status(request.getSession().getId(), id, offset);
    }

    @PostMapping("/tasks/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id, HttpServletRequest request) {
        scripts.cancel(request.getSession().getId(), id);
        return Collections.singletonMap("success", true);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException error) {
        return Collections.singletonMap("message", error.getMessage());
    }

    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> unreadable(IOException error) {
        return Collections.singletonMap("message", "无法读取脚本文件，请检查压缩包和文本编码是否有效");
    }
}
