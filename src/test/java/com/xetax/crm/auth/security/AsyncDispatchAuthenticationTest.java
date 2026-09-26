package com.xetax.crm.auth.security;

import com.xetax.crm.auth.user.AuthUserEntity;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The AI assistant answers over SSE, and an SSE response finishes with a
 * second pass through the servlet filters — an ASYNC dispatch.
 *
 * <p>{@code OncePerRequestFilter.shouldNotFilterAsyncDispatch()} returns true
 * by default, so this filter sat that pass out. Sessions are STATELESS, so
 * nothing put the authentication back either, and {@code
 * .anyRequest().authenticated()} then refused the user's own request. By that
 * point the SSE headers and the answer were already on the wire, which is why
 * production logged
 *
 * <pre>Unable to handle the Spring Security Exception because the response is already committed.</pre>
 *
 * <p>and the browser saw a failed request for an answer the server had
 * already produced and stored — it only appeared after a reload.
 */
class AsyncDispatchAuthenticationTest {

    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private JWTService jwtService;
    private JwtAuthenticationFilter filter;
    private String accessToken;

    @BeforeEach
    void setUp() {
        jwtService = new JWTService(
                "test-secret-key-that-is-long-enough-for-hs256", 3600, 7200, "xetax-test");

        AuthUserEntity user = new AuthUserEntity();
        user.setId(USER);
        user.setEmail("owner@example.com");
        accessToken = jwtService.generateAccessToken(user);

        UserCacheService users = mock(UserCacheService.class);
        when(users.findById(USER)).thenReturn(Optional.of(user));

        filter = new JwtAuthenticationFilter();
        filter.jwtService = jwtService;
        filter.userCacheService = users;

        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void runFilter(DispatcherType dispatcherType) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/chat/stream");
        request.setDispatcherType(dispatcherType);
        request.addHeader("Authorization", "Bearer " + accessToken);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    @Test
    void theFirstPassAuthenticatesTheUser() throws Exception {
        runFilter(DispatcherType.REQUEST);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(USER,
                ((AuthUserEntity) SecurityContextHolder.getContext().getAuthentication()
                        .getPrincipal()).getId());
    }

    @Test
    void theAsyncPassThatFinishesAnSseResponseAuthenticatesToo() throws Exception {
        // The bug: without this, the SSE request is denied on its way out and
        // the answer never reaches the browser.
        runFilter(DispatcherType.ASYNC);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication(),
                "the ASYNC dispatch ran unauthenticated — Spring Security will refuse "
                        + "a response it has already committed");
    }

    @Test
    void anAsyncPassWithoutATokenStaysAnonymous() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/chat/stream");
        request.setDispatcherType(DispatcherType.ASYNC);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void theErrorPassIsStillLeftAlone() throws Exception {
        // Unchanged: error dispatches are handled by the error controller.
        runFilter(DispatcherType.ERROR);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
