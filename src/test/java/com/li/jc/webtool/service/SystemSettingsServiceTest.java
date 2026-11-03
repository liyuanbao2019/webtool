package com.li.jc.webtool.service;
import com.li.jc.webtool.config.*;
import com.li.jc.webtool.controller.SystemSettingsController;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class SystemSettingsServiceTest {
    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.context.properties.EnableConfigurationProperties(AuthConfig.class)
    static class MinimalApplication { }
    @TempDir Path dir;
    StandardEnvironment env() throws Exception {
        StandardEnvironment env=new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("testLocation",Collections.singletonMap("app.system-settings-file",dir.resolve("settings.json").toString())));
        for(PropertySource<?> source:new YamlPropertySourceLoader().load("Config resource test",new ClassPathResource("application.yml"))) env.getPropertySources().addLast(source);
        return env;
    }
    SystemSettingsService.Field field(SystemSettingsService.View v,String key) { return v.getFields().stream().filter(f->key.equals(f.getKey())).findFirst().get(); }
    @Test void allYamlFieldsEditableSecretsRetainedAndSettingsLoadAfterRestart() throws Exception {
        StandardEnvironment env=env(); SystemSettingsService service=new SystemSettingsService(env);
        SystemSettingsService.View v=service.get();
        assertTrue(v.getFields().size()>70);
        assertNull(field(v,"auth.users.jkyw").getValue());
        assertFalse(field(v,"security.csrf.token-timeout").isSecret());
        field(v,"server.port").setValue("9191");
        SystemSettingsService.View saved=service.save(v,"jkyw");
        assertTrue(saved.isRestartRequired());
        assertEquals("9090",env.getProperty("server.port"));
        StandardEnvironment restarted=env(); new SystemSettingsEnvironment().postProcessEnvironment(restarted,null);
        assertEquals("9191",restarted.getProperty("server.port"));
        assertNotNull(restarted.getProperty("auth.users.jkyw"));
        assertFalse(new SystemSettingsService(restarted).get().isRestartRequired());
    }
    @Test void deletedAccountsDoNotReappearAndBackupsConflictsAreHandled() throws Exception {
        StandardEnvironment env=env(); SystemSettingsService service=new SystemSettingsService(env);
        SystemSettingsService.View first=service.get();
        String oldVersion=first.getVersion();
        first.getFields().removeIf(f->f.getKey().equals("auth.users.lijincai"));
        service.save(first,"jkyw");
        assertThrows(ResponseStatusException.class,()->service.save(first,"jkyw"));
        SystemSettingsService.View next=service.get(); field(next,"server.port").setValue("9292"); service.save(next,"jkyw");
        assertTrue(Files.exists(dir.resolve("settings.json.bak")));
        StandardEnvironment restarted=env(); new SystemSettingsEnvironment().postProcessEnvironment(restarted,null);
        assertNull(restarted.getProperty("auth.users.lijincai"));
    }
    @Test void invalidSettingsNeverReplaceValidFile() throws Exception {
        SystemSettingsService service=new SystemSettingsService(env());
        SystemSettingsService.View v=service.get();field(v,"server.port").setValue("99999");
        assertThrows(IllegalArgumentException.class,()->service.save(v,"jkyw"));
        assertFalse(Files.exists(dir.resolve("settings.json")));
        SystemSettingsService.View clean=service.get(); clean.getFields().removeIf(f->f.getKey().equals("auth.users.jkyw"));
        final SystemSettingsService.View removed=clean;
        assertThrows(IllegalArgumentException.class,()->service.save(removed,"jkyw"));
    }
    @Test void settingsApiRequiresAdminEvenWithoutGlobalAuthInterceptor() throws Exception {
        SecurityConfig security=new SecurityConfig();security.getSqlCheck().getAdminUsers().add("jkyw");
        MockMvc api=MockMvcBuilders.standaloneSetup(new SystemSettingsController(new SystemSettingsService(env()),security)).build();
        api.perform(get("/api/system-settings")).andExpect(status().isUnauthorized());
        MockHttpSession normal=new MockHttpSession();normal.setAttribute(AuthInterceptor.SESSION_USER_KEY,"normal");
        api.perform(get("/api/system-settings").session(normal)).andExpect(status().isForbidden());
        normal.setAttribute(AuthInterceptor.SESSION_USER_KEY,"jkyw");
        api.perform(get("/api/system-settings").session(normal)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void springBootDiscoversManagedSettingsBeforeBindingBeans() throws Exception {
        SystemSettingsService service = new SystemSettingsService(env());
        SystemSettingsService.View view = service.get();
        view.getFields().removeIf(f -> f.getKey().equals("auth.users.lijincai"));
        field(view,"server.port").setValue("9191");
        service.save(view,"jkyw");
        org.springframework.boot.SpringApplication app = new org.springframework.boot.SpringApplication(MinimalApplication.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        app.setLogStartupInfo(false);
        try (org.springframework.context.ConfigurableApplicationContext context = app.run(
                "--app.system-settings-file=" + dir.resolve("settings.json"))) {
            assertEquals("9191",context.getEnvironment().getProperty("server.port"));
            assertTrue(context.getBean(AuthConfig.class).getUsers().containsKey("jkyw"));
            assertFalse(context.getBean(AuthConfig.class).getUsers().containsKey("lijincai"));
        }
    }
}
