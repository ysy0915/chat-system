package com.example.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import jakarta.servlet.http.HttpServletRequest;

@Service
public class AuditService {
    
    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");
    
    public void log(String eventType, String userId, String username, 
                    HttpServletRequest request, String detail, String result) {
        String ip = getClientIp(request);
        String ua = request.getHeader("User-Agent");
        
        // 输出到专用审计日志文件
        auditLog.info("[AUDIT] type={} user={}({}) ip={} result={} detail={}", 
                      eventType, username, userId, ip, result, detail);
    }
    
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Real-IP");
        if (ip == null || ip.isEmpty()) {
            ip = request.getHeader("X-Forwarded-For");
        }
        if (ip == null || ip.isEmpty()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
