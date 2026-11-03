package com.li.jc.webtool.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.*;
import java.nio.file.*;
import java.util.*;

/** Loads the complete managed snapshot before configuration beans are bound. */
public class SystemSettingsEnvironment implements EnvironmentPostProcessor, Ordered {
    public static final String SOURCE = "webtoolSystemSettings";
    public static Path file(Environment env) {
        return Paths.get(env.getProperty("app.system-settings-file", "./config/system-settings.json")).toAbsolutePath().normalize();
    }
    public static boolean managed(String key) {
        return key.matches("(?:server|auth|ssh|spring|app|oracle|audit|security|logging)\\..+")
            && !key.equals("app.system-settings-file") && !key.startsWith("spring.config.");
    }
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
    @Override public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        Path file = file(env);
        if (!Files.exists(file)) return;
        try {
            Map<String,Object> saved = new ObjectMapper().readValue(file.toFile(), new TypeReference<LinkedHashMap<String,Object>>() {});
            if (saved == null || saved.isEmpty() || saved.keySet().stream().anyMatch(k -> !managed(k)))
                throw new IllegalArgumentException("Invalid managed settings");
            // Remove old YAML values so removed accounts/list entries do not reappear via fallback.
            List<PropertySource<?>> sources = new ArrayList<>();
            env.getPropertySources().forEach(sources::add);
            for (PropertySource<?> source : sources) {
                if (source.getName().startsWith("Config resource") && source instanceof EnumerablePropertySource) {
                    Map<String,Object> remaining = new LinkedHashMap<>();
                    for (String key : ((EnumerablePropertySource<?>) source).getPropertyNames())
                        if (!managed(key)) remaining.put(key, source.getProperty(key));
                    env.getPropertySources().replace(source.getName(), new MapPropertySource(source.getName(), remaining));
                }
            }
            MapPropertySource override = new MapPropertySource(SOURCE, saved);
            if (env.getPropertySources().contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
                env.getPropertySources().addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, override);
            else env.getPropertySources().addFirst(override);
        } catch (Exception e) { throw new IllegalStateException("Cannot read system settings: " + file + "; restore its .bak backup", e); }
    }
}
