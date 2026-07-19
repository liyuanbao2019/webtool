package com.gxcj.xjtool.config;

import com.gxcj.xjtool.service.RateLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseApiSecurityInterceptorTest {

    @Test
    void newDatabaseExecuteRoutePassesThroughRateLimiter() throws Exception {
        SecurityConfig config = new SecurityConfig();
        config.getRateLimit().setEnabled(true);
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.allowIpRequest(anyString())).thenReturn(false);
        RateLimitInterceptor interceptor = new RateLimitInterceptor();
        ReflectionTestUtils.setField(interceptor, "securityConfig", config);
        ReflectionTestUtils.setField(interceptor, "rateLimitService", rateLimitService);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/database/execute");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(rateLimitService).allowIpRequest("127.0.0.1");
    }

    @Test
    void nonSensitiveDatabaseRouteDoesNotConsumeRateLimit() throws Exception {
        SecurityConfig config = new SecurityConfig();
        config.getRateLimit().setEnabled(true);
        RateLimitService rateLimitService = mock(RateLimitService.class);
        RateLimitInterceptor interceptor = new RateLimitInterceptor();
        ReflectionTestUtils.setField(interceptor, "securityConfig", config);
        ReflectionTestUtils.setField(interceptor, "rateLimitService", rateLimitService);

        assertTrue(interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/database/datasources"),
                new MockHttpServletResponse(), new Object()));
        verify(rateLimitService, never()).allowIpRequest(anyString());
    }

    @Test
    void newResultEditRouteKeepsForcedOriginCheckWhenGlobalCheckIsDisabled() throws Exception {
        SecurityConfig config = new SecurityConfig();
        config.getOriginCheck().setEnabled(false);
        OriginCheckInterceptor interceptor = new OriginCheckInterceptor();
        ReflectionTestUtils.setField(interceptor, "securityConfig", config);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/database/result-edits/commit");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertTrue(response.getStatus() == 403);
    }
}
