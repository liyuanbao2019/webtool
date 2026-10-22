package com.li.jc.webtool.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Reads data files only; never invokes the SecureCRT VBScript or bundled executables. */
@Service
public class ScriptImportService {
    public static final int MAX_BYTES = 256 * 1024;
    private static final String PREFIX = "%,#@,%";
    private static final Pattern BLOCK = Pattern.compile("^%,#@,%%(?:[a-fA-F0-9]{32}%)+$");

    @Data
    @AllArgsConstructor
    public static class ImportedScript {
        private String name;
        private String format;
        private String content;
        private int blockCount;
    }

    public ImportedScript importFile(String name, InputStream input) throws IOException {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) return importZip(input);
        if (!lower.endsWith(".bms") && !lower.endsWith(".sh")) {
            throw new IllegalArgumentException("请选择 ZIP、BMS 或 Shell (.sh) 脚本");
        }
        return parse(name, decode(readLimited(input, MAX_BYTES)));
    }

    private ImportedScript importZip(InputStream input) throws IOException {
        byte[] candidate = null;
        String candidateName = null;
        int entries = 0;
        int total = 0;
        // Entry names are labels only. No archive entries are extracted to the filesystem.
        try (ZipInputStream zip = new ZipInputStream(input, Charset.forName("GB18030"))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 100) throw new IllegalArgumentException("压缩包文件数量过多");
                byte[] data = readLimited(zip, 1024 * 1024 - total);
                total += data.length;
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".bms") || lower.endsWith(".sh")) {
                    if (candidate != null) throw new IllegalArgumentException("压缩包中存在多个脚本，请单独导入需要执行的 BMS/SH 文件");
                    if (data.length > MAX_BYTES) throw new IllegalArgumentException("脚本不能超过 256KB");
                    candidate = data;
                    candidateName = name.substring(name.lastIndexOf('/') + 1);
                }
            }
        }
        if (candidate == null) throw new IllegalArgumentException("压缩包中未找到 BMS/SH 脚本；VBS/EXE 不能在服务器执行");
        return parse(candidateName, decode(candidate));
    }

    public ImportedScript parse(String name, String text) {
        String content = normalize(text);
        if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".bms")) return parseBms(name, content);
        return new ImportedScript(name, "shell", content, 1);
    }

    public String normalize(String text) {
        if (text == null) throw new IllegalArgumentException("脚本内容不能为空");
        String content = text.replace("\r\r\n", "\n").replace("\r\n", "\n").replace('\r', '\n');
        if (content.startsWith("\uFEFF")) content = content.substring(1);
        if (content.trim().isEmpty()) throw new IllegalArgumentException("脚本内容不能为空");
        if (content.indexOf('\0') >= 0 || content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("脚本必须为文本且不能超过 256KB");
        }
        return content.endsWith("\n") ? content : content + "\n";
    }

    private ImportedScript parseBms(String name, String content) {
        List<String> metadata = new ArrayList<>();
        List<String> sections = new ArrayList<>();
        String marker = null;
        StringBuilder body = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            if (BLOCK.matcher(line).matches()) {
                if (marker == null) {
                    marker = line;
                    body.setLength(0);
                } else {
                    if (!marker.equals(line)) throw new IllegalArgumentException("BMS 命令块标记不匹配");
                    sections.add(printLine(marker) + "(\n:\n" + body + "\n)\n"
                            + "__webtool_rc=$?; if [ \"$__webtool_rc\" -ne 0 ]; then __webtool_result=$__webtool_rc; fi\n"
                            + printLine(marker));
                    marker = null;
                }
            } else if (marker != null) {
                if (line.startsWith(PREFIX)) throw new IllegalArgumentException("BMS 命令块包含不支持的交互指令");
                body.append(line).append('\n');
            } else if (line.startsWith(PREFIX)) {
                // SecureCRT user_input_ip placeholders contain no executable commands.
                metadata.add(line);
            } else if (!line.trim().isEmpty()) {
                throw new IllegalArgumentException("BMS 命令块之外存在无法识别的内容");
            }
        }
        if (marker != null || sections.isEmpty()) throw new IllegalArgumentException("BMS 命令块不完整或为空");
        StringBuilder script = new StringBuilder("#!/usr/bin/env bash\n__webtool_result=0\nprintf 'bms_offline_start%s\\n' \"$(date +%s)\"\n");
        for (String line : metadata) script.append(printLine(line));
        for (String section : sections) script.append(section);
        script.append("printf 'bms_offline_end%s\\n' \"$(date +%s)\"\nexit \"$__webtool_result\"\n");
        return new ImportedScript(name, "bms", normalize(script.toString()), sections.size());
    }

    private String printLine(String line) {
        return "printf '%s\\n' '" + line.replace("'", "'\"'\"'") + "'\n";
    }

    private String decode(byte[] data) throws CharacterCodingException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            return Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        }
    }

    private byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IllegalArgumentException("脚本或压缩包解压后大小超出限制");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
