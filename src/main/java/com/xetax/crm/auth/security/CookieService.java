package com.xetax.crm.auth.security;

import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
@Getter
public class CookieService {
    private final Logger logger = LoggerFactory.getLogger(CookieService.class);

    private final String refreshTokenCookieName;
    private final boolean cookieHttponly;
    private final boolean cookieSecure;
    private final String cookieDomain;
    private final String cookieSameSite;

    public CookieService(@Value("${security.jwt.refresh-token-cookie-name}") String refreshTokenCookieName,
                         @Value("${security.jwt.cookie-http-only}") boolean cookieHttponly,
                         @Value("${security.jwt.cookie-secure}") boolean cookieSecure,
                         @Value("${security.jwt.cookie-domain}") String cookieDomain,
                         @Value("${security.jwt.cookie-same-site}") String cookieSameSite) {
        this.refreshTokenCookieName = refreshTokenCookieName;
        this.cookieHttponly = cookieHttponly;
        this.cookieSecure = cookieSecure;
        this.cookieDomain = cookieDomain;
        this.cookieSameSite = cookieSameSite;
    }

    public void attachRefreshCookie(HttpServletResponse httpServletResponse, String val, int maxAge) {
        logger.info("Attaching Cookie with name :{}", refreshTokenCookieName);
        var responseCookieBuilder = ResponseCookie.from(refreshTokenCookieName, val)
                .httpOnly(cookieHttponly)
                .secure(cookieSecure)
                .path("/")
                .maxAge(maxAge)
                .sameSite(cookieSameSite);
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            responseCookieBuilder.domain(cookieDomain);
        }
        ResponseCookie responseCookie = responseCookieBuilder.build();
        httpServletResponse.addHeader(HttpHeaders.SET_COOKIE, responseCookie.toString());
    }

    public void clearRefreshCookie(HttpServletResponse httpServletResponse, String val, int maxAge) {
        var responseCookieBuilder = ResponseCookie.from(refreshTokenCookieName, "")
                .httpOnly(cookieHttponly)
                .secure(cookieSecure)
                .path("/")
                .maxAge(0)
                .sameSite(cookieSameSite);
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            responseCookieBuilder.domain(cookieDomain);
        }
        ResponseCookie responseCookie = responseCookieBuilder.build();
        httpServletResponse.addHeader(HttpHeaders.SET_COOKIE, responseCookie.toString());
    }
}
