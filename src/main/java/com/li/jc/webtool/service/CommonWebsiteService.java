package com.li.jc.webtool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.jc.webtool.config.ServerConfig;
import com.li.jc.webtool.model.CommonWebsite;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.MalformedURLException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Shared catalog: persist successfully before replacing the live snapshot. */
@Service
@Slf4j
public class CommonWebsiteService {
    private final ServerConfig defaults;
    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile Settings current;

    public CommonWebsiteService(ServerConfig defaults,
            @Value("${app.common-websites-config-file:./data/common-websites.json}") String file) {
        this.defaults = defaults;
        this.file = Paths.get(file).toAbsolutePath().normalize();
    }

    @Data
    public static class Settings {
        private boolean enabled = true;
        private List<CommonWebsite> websites = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
    }

    @PostConstruct
    public synchronized void load() {
        Settings loaded = null;
        List<String> warnings = new ArrayList<>();
        if (Files.exists(file)) {
            try {
                loaded = mapper.readValue(file.toFile(), Settings.class);
                if (loaded == null) throw new IllegalArgumentException("网站配置为空");
            } catch (IOException | IllegalArgumentException e) {
                log.warn("无法读取常用网站配置 {}，保留原文件并暂用 YAML 初始配置", file);
                warnings.add("无法读取已保存的网站配置，原文件已保留，暂用 YAML 初始配置；请检查文件或在此页面重新配置并保存。");
            }
        }
        if (loaded == null) {
            loaded = new Settings();
            loaded.setEnabled(defaults.isCommonWebsitesEnabled());
            loaded.setWebsites(defaults.getCommonWebsites());
        }
        current = loadForEditing(loaded, warnings);
    }

    /** Legacy configuration must remain editable even when an individual entry is invalid. */
    private Settings loadForEditing(Settings loaded, List<String> warnings) {
        Settings result = new Settings();
        result.setEnabled(loaded.isEnabled());
        result.setWarnings(warnings);
        if (loaded.getWebsites() == null) {
            warnings.add("网站列表为空，已按空列表加载，请在此页面检查并保存。");
            return result;
        }
        if (loaded.getWebsites().size() > 100) warnings.add("原配置超过 100 个网站，请调整数量后保存。");
        for (int i = 0; i < loaded.getWebsites().size(); i++) {
            CommonWebsite item = loaded.getWebsites().get(i);
            CommonWebsite editable = new CommonWebsite();
            if (item != null) {
                editable.setName(item.getName()); editable.setUrl(item.getUrl()); editable.setIcon(item.getIcon());
            }
            if (trim(editable.getIcon()).isEmpty()) editable.setIcon("fas fa-globe");
            try {
                result.getWebsites().add(validateWebsite(editable));
            } catch (IllegalArgumentException e) {
                result.getWebsites().add(editable);
                String warning = "第 " + (i + 1) + " 个网站未展示，请修正后保存：" + e.getMessage();
                warnings.add(warning);
                log.warn("常用网站配置异常（不影响启动）：{}", warning);
            }
        }
        return result;
    }

    public Settings settings() {
        return copy(current);
    }

    public List<CommonWebsite> visibleWebsites() {
        Settings snapshot = settings();
        if (!snapshot.isEnabled()) return Collections.emptyList();
        List<CommonWebsite> visible = new ArrayList<>();
        for (CommonWebsite site : snapshot.getWebsites()) {
            try { visible.add(validateWebsite(site)); } catch (IllegalArgumentException ignored) { }
        }
        return visible;
    }

    public synchronized Settings save(Settings request) {
        Settings normalized = validate(request);
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "common-websites-", ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), normalized);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("保存常用网站失败，请检查目录写入权限: " + file.getParent(), e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
        current = normalized;
        return copy(normalized);
    }

    private Settings validate(Settings settings) {
        if (settings == null || settings.getWebsites() == null) throw new IllegalArgumentException("网站列表不能为空，请传入数组");
        if (settings.getWebsites().size() > 100) throw new IllegalArgumentException("最多可配置 100 个常用网站");
        Settings result = new Settings();
        result.setEnabled(settings.isEnabled());
        for (CommonWebsite site : settings.getWebsites()) {
            result.getWebsites().add(validateWebsite(site));
        }
        return result;
    }

    private CommonWebsite validateWebsite(CommonWebsite site) {
        if (site == null) throw new IllegalArgumentException("网站配置不能为空");
        String name = trim(site.getName());
        String url = trim(site.getUrl());
        String icon = trim(site.getIcon());
        if (name.isEmpty() || name.length() > 100) throw new IllegalArgumentException("网站名称须为 1–100 个字符");
        if (url.isEmpty() || url.length() > 2048) throw new IllegalArgumentException("网站地址须为 1–2048 个字符");
        if (!url.matches("(?i)^https?://.*")) {
            if (url.contains("://") || url.matches("(?i)^(javascript|data|file|vbscript|mailto):.*")) {
                throw new IllegalArgumentException("网站地址仅支持 HTTP 或 HTTPS");
            }
            url = "http://" + url;
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            // URI#getHost rejects browser-compatible intranet names containing underscores.
            // URL parses the authority without performing a DNS lookup.
            URL address = new URL(url);
            if ((!"http".equals(scheme) && !"https".equals(scheme)) || trim(address.getHost()).isEmpty()
                    || address.getUserInfo() != null || address.getPort() > 65535 || address.getPort() == 0
                    || address.getPort() < -1) {
                throw new IllegalArgumentException("网站地址无效，请填写 HTTP/HTTPS 地址且不要包含账号密码");
            }
        } catch (URISyntaxException | MalformedURLException e) {
            throw new IllegalArgumentException("网站地址格式不正确");
        }
        if (icon.isEmpty()) icon = "fas fa-globe";
        if (!icon.matches("(?:fas|far|fab) fa-[a-z0-9-]{1,60}")) throw new IllegalArgumentException("网站图标格式不正确");
        CommonWebsite normalized = new CommonWebsite();
        normalized.setName(name); normalized.setUrl(url); normalized.setIcon(icon);
        return normalized;
    }

    private Settings copy(Settings source) {
        Settings result = new Settings();
        result.setEnabled(source.isEnabled());
        result.setWarnings(new ArrayList<>(source.getWarnings()));
        for (CommonWebsite item : source.getWebsites()) {
            CommonWebsite site = new CommonWebsite();
            site.setName(item.getName()); site.setUrl(item.getUrl()); site.setIcon(item.getIcon());
            result.getWebsites().add(site);
        }
        return result;
    }

    private String trim(String text) { return text == null ? "" : text.trim(); }
}
