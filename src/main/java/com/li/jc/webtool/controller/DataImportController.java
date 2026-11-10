package com.li.jc.webtool.controller;

import com.li.jc.webtool.dto.DataImportRequest;
import com.li.jc.webtool.service.dataimport.DataImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** SQL tool: import CSV / delimited text files into a table. */
@Slf4j
@RestController
@RequestMapping("/api/database/import")
@RequiredArgsConstructor
public class DataImportController {

    private final DataImportService dataImportService;

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file, HttpSession session) throws IOException {
        return dataImportService.upload(file, session.getId(), currentUser(session));
    }

    /** Excel uploads: switch to another worksheet. */
    @PostMapping("/sheet")
    public Map<String, Object> selectSheet(@RequestParam String importId, @RequestParam int sheetIndex,
            HttpSession session) throws IOException {
        return dataImportService.selectSheet(importId, sheetIndex, session.getId());
    }

    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody DataImportRequest.Preview request, HttpSession session)
            throws IOException {
        return dataImportService.preview(request, session.getId());
    }

    @GetMapping("/table-columns")
    public Map<String, Object> tableColumns(@RequestParam int datasourceIndex, @RequestParam String table)
            throws SQLException {
        return dataImportService.tableColumns(datasourceIndex, table);
    }

    /** New table mode: suggest table name, column names and types from the file. */
    @PostMapping("/infer-columns")
    public Map<String, Object> inferColumns(@RequestBody DataImportRequest.InferColumns request, HttpSession session)
            throws IOException, SQLException {
        return dataImportService.inferColumns(request, session.getId());
    }

    /** New table mode: validate the definition and show the CREATE TABLE statement. */
    @PostMapping("/create-ddl")
    public Map<String, Object> createDdl(@RequestBody DataImportRequest.CreateTablePreview request) throws SQLException {
        return dataImportService.previewCreateTable(request);
    }

    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody DataImportRequest.Start request, HttpSession session)
            throws IOException, SQLException {
        return dataImportService.start(request, session.getId(), currentUser(session));
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String importId, HttpSession session) {
        return dataImportService.status(importId, session.getId());
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel(@RequestParam String importId, HttpSession session) {
        return dataImportService.cancel(importId, session.getId());
    }

    @PostMapping("/discard")
    public Map<String, Object> discard(@RequestParam String importId, HttpSession session) {
        dataImportService.discard(importId, session.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }

    @GetMapping("/errors")
    public ResponseEntity<Resource> errors(@RequestParam String importId,
            @RequestParam(defaultValue = "report") String type, HttpSession session) {
        DataImportService.ErrorFile file = dataImportService.errorFile(importId, session.getId(), type);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.getContentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.getFileName(), StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(new FileSystemResource(file.getPath()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, SQLException.class})
    public ResponseEntity<Map<String, Object>> handleError(Exception exception) {
        log.warn("Data import request failed: {}", exception.getMessage());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("message", exception.getMessage());
        return ResponseEntity.badRequest().body(result);
    }

    private String currentUser(HttpSession session) {
        Object user = session.getAttribute("LOGIN_USER");
        return user == null ? "unknown" : String.valueOf(user);
    }
}
