package com.li.jc.webtool;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;

/**
 * WebTool 主应用程序入口
 * 轻量级 Web SSH 终端与服务器管理工具
 *
 * @author 李金才 (li.jc)
 * @version 1.0.0-SNAPSHOT
 * @since 2026-01-16
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties
@EnableCaching
public class WebToolApplication {
    public static void main(String[] args) {
        SpringApplication.run(WebToolApplication.class, args);
    }
}
