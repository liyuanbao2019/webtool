package com.li.jc.webtool.controller;
import com.li.jc.webtool.config.AuthInterceptor;
import com.li.jc.webtool.config.SecurityConfig;
import com.li.jc.webtool.service.SystemSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import javax.servlet.http.*;
import java.util.*;
@RestController
@RequestMapping("/api/system-settings")
public class SystemSettingsController {
    private final SystemSettingsService settings;
    private final SecurityConfig security;
    public SystemSettingsController(SystemSettingsService settings, SecurityConfig security) { this.settings=settings; this.security=security; }
    private String admin(HttpServletRequest request) {
        HttpSession session=request.getSession(false);
        Object user=session == null ? null : session.getAttribute(AuthInterceptor.SESSION_USER_KEY);
        if(user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");
        if(!security.getSqlCheck().getAdminUsers().contains(user.toString())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"系统设置仅限管理员访问");
        return user.toString();
    }
    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    @GetMapping public SystemSettingsService.View get(HttpServletRequest request) { admin(request); return settings.get(); }
    @PutMapping public SystemSettingsService.View save(@RequestBody SystemSettingsService.View view,HttpServletRequest request) { return settings.save(view,admin(request)); }
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> status(ResponseStatusException e) { return ResponseEntity.status(e.getStatus()).body(Collections.singletonMap("message",e.getReason())); }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(IllegalArgumentException e) { return Collections.singletonMap("message",e.getMessage()); }
    @ExceptionHandler(IllegalStateException.class) @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String,String> failed(IllegalStateException e) { return Collections.singletonMap("message",e.getMessage()); }
}
