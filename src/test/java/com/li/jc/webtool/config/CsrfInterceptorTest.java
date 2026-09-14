package com.li.jc.webtool.config;

import com.li.jc.webtool.service.CsrfTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CsrfInterceptorTest {

    @Test
    void invalidTokenMarksForbiddenResponseAsCsrfFailure() throws Exception {
        SecurityConfig config = new SecurityConfig();
        config.getCsrf().setEnabled(true);
        CsrfTokenService tokenService = mock(CsrfTokenService.class);
        when(tokenService.validateToken("expired-token")).thenReturn(false);

        CsrfInterceptor interceptor = new CsrfInterceptor();
        ReflectionTestUtils.setField(interceptor, "securityConfig", config);
        ReflectionTestUtils.setField(interceptor, "csrfTokenService", tokenService);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/oracle/export/excel");
        request.addHeader("X-CSRF-Token", "expired-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
        assertEquals("invalid-token", response.getHeader("X-CSRF-Error"));
    }
}
