package com.li.jc.webtool.controller;

import com.li.jc.webtool.service.CsrfTokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * CSRF Token控制器
 * 提供Token获取接口
 */
@RestController
@RequestMapping("/api/csrf")
public class CsrfController {

    @Autowired
    private CsrfTokenService csrfTokenService;

    /**
     * 获取CSRF Token
     * 
     * @return Token信息
     */
    @GetMapping("/token")
    public Map<String, Object> getToken() {
        Map<String, Object> result = new HashMap<>();

        String token = csrfTokenService.generateToken();
        if (token != null) {
            result.put("success", true);
            result.put("token", token);
        } else {
            result.put("success", false);
            result.put("message", "Token生成失败");
        }

        return result;
    }
}
