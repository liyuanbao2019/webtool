package com.gxcj.xjtool.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC配置类
 * 用于注册拦截器等配置
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

        @Value("${spring.mvc.async.request-timeout:-1}")
        private long asyncRequestTimeout;

        @Autowired
        private AuthInterceptor authInterceptor;

        @Autowired
        private CsrfInterceptor csrfInterceptor;

        @Autowired
        private RateLimitInterceptor rateLimitInterceptor;

        @Autowired
        private OriginCheckInterceptor originCheckInterceptor;

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
                // 注册登录拦截器，拦截所有请求
                registry.addInterceptor(authInterceptor)
                                .addPathPatterns("/**");

                // 注册CSRF拦截器，拦截所有请求
                registry.addInterceptor(csrfInterceptor)
                                .addPathPatterns("/**");

                // 注册限流拦截器，拦截所有请求
                registry.addInterceptor(rateLimitInterceptor)
                                .addPathPatterns("/**");

                // 注册来源验证拦截器，拦截所有请求
                registry.addInterceptor(originCheckInterceptor)
                                .addPathPatterns("/**");
        }

        @Override
        public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                // 设置异步请求超时时间，默认-1（不超时），防止SFTP大文件下载时被Spring强行中断连接
                configurer.setDefaultTimeout(asyncRequestTimeout);
        }
}
