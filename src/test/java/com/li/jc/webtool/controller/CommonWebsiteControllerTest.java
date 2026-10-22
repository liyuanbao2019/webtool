package com.li.jc.webtool.controller;

import com.li.jc.webtool.config.ServerConfig;
import com.li.jc.webtool.service.CommonWebsiteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CommonWebsiteControllerTest {
    @TempDir Path directory;

    @Test void managementAndExistingHomepageEndpointShareTheSavedSettings() throws Exception {
        CommonWebsiteService service = new CommonWebsiteService(new ServerConfig(), directory.resolve("websites.json").toString());
        service.load();
        ServerController server = new ServerController();
        ReflectionTestUtils.setField(server, "commonWebsiteService", service);
        MockMvc api = MockMvcBuilders.standaloneSetup(server, new CommonWebsiteController(service)).build();
        api.perform(put("/api/server/common-websites/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"websites\":[{\"name\":\"Portal\",\"url\":\"intranet:8080\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.websites[0].url").value("http://intranet:8080"));
        api.perform(get("/api/server/common-websites")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Portal"));
        api.perform(put("/api/server/common-websites/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"websites\":[{\"name\":\"Portal\",\"url\":\"http://intranet:8080\"}]}"))
                .andExpect(status().isOk());
        api.perform(get("/api/server/common-websites")).andExpect(jsonPath("$").isEmpty());
        api.perform(get("/api/server/common-websites/config")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.websites[0].name").value("Portal"));
        api.perform(put("/api/server/common-websites/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"websites\":[{\"name\":\"Bad\",\"url\":\"javascript:alert(1)\"}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
    }
}
