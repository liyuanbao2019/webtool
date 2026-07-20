package com.li.jc.webtool.config;

import com.li.jc.webtool.model.ServerGroup;
import com.li.jc.webtool.model.ServerInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Slf4j
@Component
public class ExcelServerConfigLoader implements ApplicationListener<ApplicationReadyEvent> {

    @Autowired
    private ServerConfig serverConfig;

    @Value("${app.servers-config-file:servers.xlsx}")
    private String serversConfigFile;

    private String loadedFileName;

    private static final Map<String, Integer> CN_HEADER_MAP = new LinkedHashMap<>();
    private static final Map<String, Integer> EN_HEADER_MAP = new LinkedHashMap<>();
    static {
        CN_HEADER_MAP.put("分组", 0);
        CN_HEADER_MAP.put("服务器名称", 1);
        CN_HEADER_MAP.put("主机", 2);
        CN_HEADER_MAP.put("端口", 3);
        CN_HEADER_MAP.put("用户名", 4);
        CN_HEADER_MAP.put("密码", 5);
        CN_HEADER_MAP.put("SU用户", 6);
        CN_HEADER_MAP.put("SU密码", 7);

        EN_HEADER_MAP.put("Group", 0);
        EN_HEADER_MAP.put("Name", 1);
        EN_HEADER_MAP.put("Host", 2);
        EN_HEADER_MAP.put("Port", 3);
        EN_HEADER_MAP.put("Username", 4);
        EN_HEADER_MAP.put("Password", 5);
        EN_HEADER_MAP.put("SU User", 6);
        EN_HEADER_MAP.put("SU Password", 7);
        EN_HEADER_MAP.put("Su User", 6);
        EN_HEADER_MAP.put("Su Password", 7);
        EN_HEADER_MAP.put("SU_User", 6);
        EN_HEADER_MAP.put("SU_Password", 7);
        EN_HEADER_MAP.put("Su_User", 6);
        EN_HEADER_MAP.put("Su_Password", 7);
    }

    public void reload() {
        if (serverConfig.getServersConfigFile() == null
                || serverConfig.getServersConfigFile().trim().isEmpty()) {
            return;
        }

        String fileName = serverConfig.getServersConfigFile().trim();
        this.loadedFileName = fileName;

        serverConfig.getServerGroups().clear();
        serverConfig.getServers().clear();

        if (fileName.toLowerCase().endsWith(".csv")) {
            loadFromCsv(fileName);
        } else if (fileName.toLowerCase().endsWith(".xlsx") || fileName.toLowerCase().endsWith(".xls")) {
            loadFromExcel(fileName);
        } else {
            loadFromYaml(fileName);
        }
        log.info("服务器配置已刷新，共 {} 个分组", serverConfig.getServerGroups().size());
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (serverConfig.getServersConfigFile() == null
                || serverConfig.getServersConfigFile().trim().isEmpty()) {
            return;
        }

        String fileName = serverConfig.getServersConfigFile().trim();
        this.loadedFileName = fileName;
        if (fileName.toLowerCase().endsWith(".csv")) {
            loadFromCsv(fileName);
        } else if (fileName.toLowerCase().endsWith(".xlsx") || fileName.toLowerCase().endsWith(".xls")) {
            loadFromExcel(fileName);
        } else {
            loadFromYaml(fileName);
        }
    }

    private InputStream openInputStream(String fileName) throws IOException {
        // 1. 优先从 JAR 所在目录下的 config/ 读（生产环境）
        Path jarDir = getJarDir();
        Path external = jarDir.resolve("config").resolve(fileName).normalize();
        if (Files.exists(external)) {
            log.info("从外部配置文件加载: {}", external);
            return new FileInputStream(external.toFile());
        }
        // 2. fallback 到 classpath（开发/IDE 环境）
        log.info("从 classpath 加载: {}", fileName);
        return new ClassPathResource(fileName).getInputStream();
    }

    private Path getJarDir() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        String jarLocation = System.getProperty("webtool.jar.location",
                System.getProperty("xjtool.jar.location", ""));
        if (!jarLocation.isEmpty()) {
            Path p = Paths.get(jarLocation).toAbsolutePath().normalize();
            if (p.getParent() != null) {
                return p.getParent();
            }
        }
        return cwd;
    }

    private Charset detectCharset(String fileName) {
        byte[] data;
        try {
            data = toByteArray(openInputStream(fileName));
        } catch (Exception e) {
            log.warn("无法读取文件 {}: {}", fileName, e.getMessage());
            return StandardCharsets.UTF_8;
        }
        // 先按 UTF-8 解码
        String utf8Str = new String(data, StandardCharsets.UTF_8);
        // 再按 GBK 解码
        String gbkStr = new String(data, Charset.forName("GBK"));

        boolean utf8HasChinese = containsChineseChars(utf8Str);
        boolean gbkHasChinese = containsChineseChars(gbkStr);
        boolean utf8HasGarbagedCJK = containsGarbageCJK(utf8Str);

        if (utf8HasGarbagedCJK && gbkHasChinese) {
            // UTF-8 解出来是乱码（大量 CJK Compatibility Ideographs），而 GBK 解出来有正常汉字
            // → 文件实际是 GBK 编码
            log.info("检测到 CSV 文件编码: GBK (UTF-8 解码出现乱码，已自动纠正)");
            return Charset.forName("GBK");
        }
        if (utf8HasChinese && !utf8HasGarbagedCJK) {
            // UTF-8 解出来有正常汉字 → 文件就是 UTF-8
            log.info("检测到 CSV 文件编码: UTF-8");
            return StandardCharsets.UTF_8;
        }
        if (gbkHasChinese) {
            log.info("检测到 CSV 文件编码: GBK (fallback)");
            return Charset.forName("GBK");
        }
        // 全是 ASCII，默认 UTF-8
        log.info("检测到 CSV 文件编码: UTF-8 (默认)");
        return StandardCharsets.UTF_8;
    }

    private byte[] toByteArray(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private boolean containsChineseChars(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            int cp = s.codePointAt(i);
            if (cp >= 0x4E00 && cp <= 0x9FFF) return true;  // CJK Unified Ideographs
            if (cp >= 0x3000 && cp <= 0x303F) return true;  // CJK Symbols
            if (cp >= 0xFF00 && cp <= 0xFFEF) return true;  // Halfwidth/Fullwidth
        }
        return false;
    }

    private boolean containsGarbageCJK(String s) {
        if (s == null) return false;
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            int cp = s.codePointAt(i);
            // CJK Compatibility Ideographs 范围: U+F900 ~ U+FAFF
            // 这些是"乱码字符"，说明UTF-8被错误地当作GBK解码（或反过来）
            if (cp >= 0xF900 && cp <= 0xFAFF) count++;
        }
        // 超过 10 个乱码字符，认为是乱码
        return count > 10;
    }

    private void loadFromCsv(String fileName) {
        try {
            Charset detected = detectCharset(fileName);
            try (InputStream raw = openInputStream(fileName);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(raw, detected))) {

                Map<String, ServerGroup> groupMap = new LinkedHashMap<>();
                int rowsRead = 0;
                String line;
                boolean first = true;

                while ((line = reader.readLine()) != null) {
                    if (first) { first = false; continue; }
                    if (line.trim().isEmpty()) continue;

                    String[] cols = parseCsvLine(line);
                    if (cols.length < 6) continue;

                    String groupName  = cols[0].trim();
                    String serverName = cols[1].trim();
                    String host       = cols[2].trim();
                    String port       = cols[3].trim();
                    String username   = cols[4].trim();
                    String password   = cols[5].trim();

                    if (host.isEmpty()) continue;

                    String effectivePassword = resolvePassword(password, host);
                    if (effectivePassword == null || effectivePassword.isEmpty()) continue;

                    String defaultGroup = serverConfig.getDefaultGroupName() != null
                            ? serverConfig.getDefaultGroupName()
                            : "Default Group";
                    final String finalGroupName   = groupName.isEmpty()  ? defaultGroup : groupName;
                    final String finalServerName = serverName.isEmpty()  ? host : serverName;
                    final String finalPort       = port.isEmpty()       ? "22"    : port;
                    final String finalUsername   = username.isEmpty()   ? ""      : username;

                    ServerInfo info = new ServerInfo();
                    info.setId(UUID.randomUUID().toString());
                    info.setName(finalServerName);
                    info.setHost(host);
                    info.setPort((int) Double.parseDouble(finalPort));
                    info.setUsername(finalUsername);
                    info.setPassword(effectivePassword);
                    parseSuPasswords(info, cols, 6);

                    groupMap.computeIfAbsent(finalGroupName, k -> {
                        ServerGroup g = new ServerGroup();
                        g.setName(finalGroupName);
                        g.setServers(new ArrayList<>());
                        return g;
                    }).getServers().add(info);
                    rowsRead++;
                }

                serverConfig.getServerGroups().addAll(groupMap.values());
                log.info("CSV 服务器配置加载完成，共 {} 个分组 {} 台服务器: {}", groupMap.size(), rowsRead, fileName);
            }
        } catch (Exception e) {
            log.warn("加载 CSV 服务器配置失败 [{}]: {}", fileName, e.getMessage());
        }
    }

    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(field.toString());
                field = new StringBuilder();
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields.toArray(new String[0]);
    }

    private void loadFromExcel(String fileName) {
        // 使用字节数组方式打开，避免 Excel 中含特殊字符的超链接导致 URISyntaxException
        try (InputStream is = openInputStream(fileName)) {
            byte[] data = toByteArray(is);
            try (org.apache.poi.openxml4j.opc.OPCPackage pkg =
                         org.apache.poi.openxml4j.opc.OPCPackage.open(new ByteArrayInputStream(data));
                 Workbook workbook = new XSSFWorkbook(pkg)) {

            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                log.warn("Excel 文件表头行为空: {}", fileName);
                return;
            }

            Map<String, Integer> headerMap = detectExcelHeader(headerRow);
            int colGroup    = getColGroup(headerMap);
            int colName     = getColName(headerMap);
            int colHost     = getColHost(headerMap);
            int colPort     = getColPort(headerMap);
            int colUsername = getColUsername(headerMap);
            int colPassword = getColPassword(headerMap);

            String defaultGroup = serverConfig.getDefaultGroupName() != null
                    ? serverConfig.getDefaultGroupName()
                    : "Default Group";

            Map<String, ServerGroup> groupMap = new LinkedHashMap<>();
            int rowsRead = 0;

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String groupName  = getCellStr(row.getCell(colGroup));
                String serverName = getCellStr(row.getCell(colName));
                String host       = getCellStr(row.getCell(colHost));
                String port       = getCellStr(row.getCell(colPort));
                String username   = getCellStr(row.getCell(colUsername));
                String password   = getCellStr(row.getCell(colPassword));

                if (host == null || host.trim().isEmpty()) continue;

                String effectivePassword = resolvePassword(password, host);
                if (effectivePassword == null || effectivePassword.isEmpty()) continue;

                final String finalGroupName   = (groupName == null || groupName.trim().isEmpty()) ? defaultGroup : groupName.trim();
                final String finalServerName = (serverName == null || serverName.trim().isEmpty()) ? host.trim() : serverName.trim();
                final String finalPort        = (port == null || port.trim().isEmpty()) ? "22" : port.trim();
                final String finalUsername    = (username == null || username.trim().isEmpty()) ? "" : username.trim();

                ServerInfo info = new ServerInfo();
                info.setId(UUID.randomUUID().toString());
                info.setGroupName(finalGroupName);
                info.setName(finalServerName);
                info.setHost(host.trim());
                info.setPort((int) Double.parseDouble(finalPort));
                info.setUsername(finalUsername);
                info.setPassword(effectivePassword);
                parseSuPasswordsFromExcel(row, info, 6);

                groupMap.computeIfAbsent(finalGroupName, k -> {
                    ServerGroup g = new ServerGroup();
                    g.setName(finalGroupName);
                    g.setServers(new ArrayList<>());
                    return g;
                }).getServers().add(info);
                rowsRead++;
            }

            serverConfig.getServerGroups().addAll(groupMap.values());
            log.info("Excel 服务器配置加载完成，共 {} 个分组 {} 台服务器: {}", groupMap.size(), rowsRead, fileName);

            } // 关闭内层 try (OPCPackage + Workbook)
        } catch (Exception e) {
            log.warn("加载 Excel 服务器配置失败 [{}]: {}", fileName, e.getMessage());
        }
    }

    private Map<String, Integer> detectExcelHeader(Row headerRow) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int col = 0; col < 20; col++) {
            Cell cell = headerRow.getCell(col);
            String val = getCellStr(cell);
            if (val == null || val.trim().isEmpty()) continue;
            String key = val.trim();
            if (CN_HEADER_MAP.containsKey(key)) {
                result.put("cn_" + CN_HEADER_MAP.get(key), col);
            } else if (EN_HEADER_MAP.containsKey(key)) {
                result.put("en_" + EN_HEADER_MAP.get(key), col);
            }
        }
        return result;
    }

    private int getOrDefault(Map<String, Integer> headerMap, int semanticIdx, int fallback) {
        Integer col = headerMap.get("en_" + semanticIdx);
        if (col == null) col = headerMap.get("cn_" + semanticIdx);
        if (col == null) col = fallback;
        return col;
    }

    private int getColGroup(Map<String, Integer> headerMap) { return getOrDefault(headerMap, 0, 0); }
    private int getColName(Map<String, Integer> headerMap)    { return getOrDefault(headerMap, 1, 1); }
    private int getColHost(Map<String, Integer> headerMap)  { return getOrDefault(headerMap, 2, 2); }
    private int getColPort(Map<String, Integer> headerMap)  { return getOrDefault(headerMap, 3, 3); }
    private int getColUsername(Map<String, Integer> headerMap) { return getOrDefault(headerMap, 4, 4); }
    private int getColPassword(Map<String, Integer> headerMap) { return getOrDefault(headerMap, 5, 5); }

    private void loadFromYaml(String fileName) {
        try (InputStream is = openInputStream(fileName)) {
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            Map<String, Object> yamlMap = yaml.load(is);
            if (yamlMap == null) return;

            Object groupsObj = yamlMap.get("server-groups");
            if (groupsObj instanceof List) {
                for (Map<String, Object> g : (List<Map<String, Object>>) groupsObj) {
                    ServerGroup sg = new ServerGroup();
                    sg.setName((String) g.get("name"));
                    sg.setServers(new ArrayList<>());

                    Object serversObj = g.get("servers");
                    if (serversObj instanceof List) {
                        for (Map<String, Object> s : (List<Map<String, Object>>) serversObj) {
                            ServerInfo si = new ServerInfo();
                            si.setId(UUID.randomUUID().toString());
                            si.setName((String) s.get("name"));
                            si.setHost((String) s.get("host"));
                            si.setPort(s.get("port") == null ? 22 : ((Number) s.get("port")).intValue());
                            si.setUsername((String) s.get("username"));
                            si.setPassword(resolvePassword((String) s.get("password"), (String) s.get("host")));
                            applyAgentFieldsFromYaml(s, si);
                            parseSuPasswordsFromYaml(s, si);
                            sg.getServers().add(si);
                        }
                    }
                    serverConfig.getServerGroups().add(sg);
                }
            }
            log.info("YAML 服务器配置加载完成: {}", fileName);
        } catch (Exception e) {
            log.warn("加载 YAML 服务器配置失败 [{}]: {}", fileName, e.getMessage());
        }
    }

    private void applyAgentFieldsFromYaml(Map<String, Object> s, ServerInfo si) {
        Object connectionMode = firstNonNull(s.get("connectionMode"), s.get("connection-mode"));
        Object agentBaseUrl = firstNonNull(s.get("agentBaseUrl"), s.get("agent-base-url"));
        Object agentId = firstNonNull(s.get("agentId"), s.get("agent-id"));
        Object agentToken = firstNonNull(s.get("agentToken"), s.get("agent-token"));

        if (connectionMode != null) {
            si.setConnectionMode(connectionMode.toString().trim());
        }
        if (agentBaseUrl != null) {
            si.setAgentBaseUrl(agentBaseUrl.toString().trim());
        }
        if (agentId != null) {
            si.setAgentId(agentId.toString().trim());
        }
        if (agentToken != null) {
            si.setAgentToken(agentToken.toString().trim());
        }
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private String resolvePassword(String rowPassword, String host) {
        if (rowPassword != null && !rowPassword.trim().isEmpty()
                && !rowPassword.trim().equals("__DEFAULT__")
                && !rowPassword.trim().equals("__SPECIAL__")) {
            return rowPassword.trim();
        }
        if (serverConfig.getPasswordOverrides() != null
                && serverConfig.getPasswordOverrides().containsKey(host)) {
            return serverConfig.getPasswordOverrides().get(host);
        }
        if (serverConfig.getCommonPassword() != null && !serverConfig.getCommonPassword().isEmpty()) {
            return serverConfig.getCommonPassword();
        }
        return null;
    }

    private String getCellStr(Cell cell) {
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case STRING:  return cell.getStringCellValue();
            case NUMERIC:  return String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN:  return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:  try { return cell.getStringCellValue(); } catch (Exception e) { return null; }
            default:       return null;
        }
    }

    /**
     * 从 CSV/String[] 数组中解析 su/sudo 用户密码对
     * 从 colStart 开始，每两个列为一组：suname, supasswd
     * 例如：cols[6]="oracle", cols[7]="oracle123", cols[8]="mysql", cols[9]="mysql456"
     */
    private void parseSuPasswords(ServerInfo info, String[] cols, int colStart) {
        for (int i = colStart; i + 1 < cols.length; i += 2) {
            String suUser = cols[i] != null ? cols[i].trim() : "";
            String suPass  = cols[i + 1] != null ? cols[i + 1].trim() : "";
            if (!suUser.isEmpty() && !suPass.isEmpty()) {
                info.getSuPasswords().put(suUser.toLowerCase(), suPass);
            }
        }
    }

    /**
     * 从 Excel Row 中解析 su/sudo 用户密码对
     * 从 colStart 开始，每两个列为一组
     */
    private void parseSuPasswordsFromExcel(Row row, ServerInfo info, int colStart) {
        for (int i = colStart; ; i += 2) {
            Cell suUserCell = row.getCell(i);
            Cell suPassCell = row.getCell(i + 1);
            if (suUserCell == null && suPassCell == null) break;
            String suUser = getCellStr(suUserCell);
            String suPass = getCellStr(suPassCell);
            if (suUser == null || suUser.trim().isEmpty()) break;
            if (suPass == null || suPass.trim().isEmpty()) break;
            info.getSuPasswords().put(suUser.trim().toLowerCase(), suPass.trim());
        }
    }

    /**
     * 从 YAML Map 中解析 su/sudo 用户密码
     * 支持格式：
     * su_passwords:
     *   oracle: oracle123
     *   mysql: mysql456
     */
    @SuppressWarnings("unchecked")
    private void parseSuPasswordsFromYaml(Map<String, Object> s, ServerInfo si) {
        Object suObj = s.get("su_passwords");
        if (suObj instanceof Map) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) suObj).entrySet()) {
                String key = entry.getKey();
                Object val = entry.getValue();
                if (key != null && val != null) {
                    si.getSuPasswords().put(key.trim().toLowerCase(), val.toString().trim());
                }
            }
        }
    }

    private Path getOutputFile(String fileName) {
        Path jarDir = getJarDir();
        Path external = jarDir.resolve("config").resolve(fileName).normalize();
        if (Files.exists(external) || Files.exists(external.getParent())) {
            return external;
        }
        return Paths.get("src", "main", "resources", fileName).toAbsolutePath().normalize();
    }

    public void saveServer(ServerInfo server, String groupName) {
        String fileName = this.loadedFileName;
        if (fileName == null) {
            fileName = serversConfigFile;
        }
        if (fileName == null || (!fileName.toLowerCase().endsWith(".xlsx") && !fileName.toLowerCase().endsWith(".xls"))) {
            log.warn("当前配置文件不支持写入Excel: {}", fileName);
            return;
        }

        Path outputPath = getOutputFile(fileName);
        log.info("写入服务器到 Excel: {} -> {}", server.getName(), outputPath);

        try {
            List<ServerInfo> allServers = collectAllServers();
            writeToExcel(outputPath, allServers);
            log.info("服务器 {} 已保存到 {}", server.getName(), outputPath);
        } catch (Exception e) {
            log.error("保存服务器失败: {}", e.getMessage(), e);
        }
    }

    public void updateServer(ServerInfo updated) {
        String fileName = this.loadedFileName;
        if (fileName == null) {
            fileName = serversConfigFile;
        }
        if (fileName == null || (!fileName.toLowerCase().endsWith(".xlsx") && !fileName.toLowerCase().endsWith(".xls"))) {
            log.warn("当前配置文件不支持写入Excel: {}", fileName);
            return;
        }

        Path outputPath = getOutputFile(fileName);
        log.info("更新服务器到 Excel: {} -> {}", updated.getName(), outputPath);

        try {
            List<ServerInfo> allServers = collectAllServers();
            for (int i = 0; i < allServers.size(); i++) {
                if (allServers.get(i).getId() != null && allServers.get(i).getId().equals(updated.getId())) {
                    allServers.set(i, updated);
                    break;
                }
            }
            writeToExcel(outputPath, allServers);
            log.info("服务器 {} 已更新到 {}", updated.getName(), outputPath);
        } catch (Exception e) {
            log.error("更新服务器失败: {}", e.getMessage(), e);
        }
    }

    public void deleteServer(String serverId) {
        String fileName = this.loadedFileName;
        if (fileName == null) {
            fileName = serversConfigFile;
        }
        if (fileName == null || (!fileName.toLowerCase().endsWith(".xlsx") && !fileName.toLowerCase().endsWith(".xls"))) {
            log.warn("当前配置文件不支持写入Excel: {}", fileName);
            return;
        }

        Path outputPath = getOutputFile(fileName);

        try {
            List<ServerInfo> allServers = collectAllServers();
            allServers.removeIf(s -> serverId.equals(s.getId()));
            writeToExcel(outputPath, allServers);
            log.info("服务器 {} 已从 Excel 中删除", serverId);
        } catch (Exception e) {
            log.error("删除服务器失败: {}", e.getMessage(), e);
        }
    }

    public ServerInfo findServerByHost(String host) {
        if (host == null || host.trim().isEmpty()) return null;

        List<ServerInfo> allServers = collectAllServers();
        for (ServerInfo s : allServers) {
            if (host.equals(s.getHost())) {
                return s;
            }
        }
        return null;
    }

    private List<ServerInfo> collectAllServers() {
        List<ServerInfo> all = new ArrayList<>();
        if (serverConfig.getServerGroups() != null) {
            for (ServerGroup g : serverConfig.getServerGroups()) {
                if (g.getServers() != null) {
                    all.addAll(g.getServers());
                }
            }
        }
        if (serverConfig.getServers() != null) {
            all.addAll(serverConfig.getServers());
        }
        return all;
    }

    private void writeToExcel(Path path, List<ServerInfo> servers) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Servers");

            Row header = sheet.createRow(0);
            CellStyle headerStyle = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setBorderTop(BorderStyle.THIN);
            headerStyle.setBorderLeft(BorderStyle.THIN);
            headerStyle.setBorderRight(BorderStyle.THIN);

            String defaultGroup = serverConfig.getDefaultGroupName() != null
                    ? serverConfig.getDefaultGroupName()
                    : "Default Group";

            String[] headers = {"Group", "Name", "Host", "Port", "Username", "Password", "SU User", "SU Password"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            for (int i = 0; i < servers.size(); i++) {
                ServerInfo s = servers.get(i);
                Row row = sheet.createRow(i + 1);

                row.createCell(0).setCellValue(s.getGroupName() != null ? s.getGroupName() : defaultGroup);
                row.createCell(1).setCellValue(s.getName() != null ? s.getName() : s.getHost());
                row.createCell(2).setCellValue(s.getHost() != null ? s.getHost() : "");
                row.createCell(3).setCellValue(s.getPort() > 0 ? s.getPort() : 22);
                row.createCell(4).setCellValue(s.getUsername() != null ? s.getUsername() : "");
                row.createCell(5).setCellValue(s.getPassword() != null ? s.getPassword() : "");

                int colIdx = 6;
                if (s.getSuPasswords() != null && !s.getSuPasswords().isEmpty()) {
                    for (Map.Entry<String, String> entry : s.getSuPasswords().entrySet()) {
                        row.createCell(colIdx++).setCellValue(entry.getKey());
                        row.createCell(colIdx++).setCellValue(entry.getValue());
                    }
                }
            }

            for (int i = 0; i < 8; i++) {
                sheet.autoSizeColumn(i);
                int width = sheet.getColumnWidth(i);
                sheet.setColumnWidth(i, width + 2560);
            }

            FileOutputStream fos = new FileOutputStream(path.toFile());
            wb.write(fos);
            fos.close();
        }
    }
}
