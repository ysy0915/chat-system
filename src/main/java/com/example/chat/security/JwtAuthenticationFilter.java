package com.example.chat.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtUtil.validateToken(token)) {
                String subject = jwtUtil.getSubject(token);
                Long uid = jwtUtil.getUserId(token);
                String role = jwtUtil.getRole(token);
                var authorities = role == null ? List.of(new SimpleGrantedAuthority("ROLE_USER")) : List.of(new SimpleGrantedAuthority(role.startsWith("ROLE_")?role:("ROLE_"+role)));
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        new org.springframework.security.core.userdetails.User(subject, "", authorities),
                        uid,
                        authorities
                );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                // optionally set an attribute for downstream handlers
                if (uid != null) {
                    request.setAttribute("uid", uid);
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
