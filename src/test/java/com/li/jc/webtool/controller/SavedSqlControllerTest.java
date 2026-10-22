package com.li.jc.webtool.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.jc.webtool.config.AuthInterceptor;
import com.li.jc.webtool.service.SavedSqlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SavedSqlControllerTest {
    @TempDir Path directory;
    private MockHttpSession session(String user) {
        MockHttpSession session = new MockHttpSession(); session.setAttribute(AuthInterceptor.SESSION_USER_KEY, user); return session;
    }

    @Test void enforcesSessionOwnershipIncludingForgedOwnerAndId() throws Exception {
        MockMvc api = MockMvcBuilders.standaloneSetup(new SavedSqlController(new SavedSqlService(directory.toString()))).build();
        String base = "/api/database/saved-sql";
        String payload = "{\"name\":\"常用查询\",\"sql\":\"select 1\",\"username\":\"bob\",\"id\":\"forged\"}";
        MockHttpSession alice = session("alice"), bob = session("bob");
        api.perform(get(base)).andExpect(status().isUnauthorized());
        api.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isUnauthorized());
        String body = api.perform(post(base).session(alice).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        String id = new ObjectMapper().readTree(body).get("id").asText();
        api.perform(get(base).session(bob).param("username", "alice")).andExpect(jsonPath("$").isEmpty());
        api.perform(get(base + "/" + id).session(alice)).andExpect(jsonPath("$.sql").value("select 1"));
        api.perform(get(base + "/" + id).session(bob)).andExpect(status().isNotFound());
        api.perform(put(base + "/" + id).session(bob).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isNotFound());
        api.perform(delete(base + "/" + id).session(bob)).andExpect(status().isNotFound());
        api.perform(post(base).session(alice).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").exists());
        api.perform(put(base + "/" + id).session(alice).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"renamed\",\"sql\":\"select 2\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sql").value("select 2"));
        api.perform(delete(base + "/" + id).session(alice)).andExpect(status().isNoContent());
        api.perform(get(base).session(alice)).andExpect(jsonPath("$").isEmpty());
    }
}
