package com.xetax.crm.auth;

import com.xetax.crm.auth.security.Oauth2SuccessHandler;
import com.xetax.crm.auth.token.entity.LoginProvider;
import com.xetax.crm.auth.user.AuthUserEntity;
import com.xetax.crm.auth.user.AuthUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Signing in with Google must hand the panel the very same user a password
 * sign-in does. It used to send a hand-built subset without platformAdmin and
 * roles, so the Platform console vanished for a Google sign-in. This runs the
 * real /auth/v1/login and the real Google success handler against the same
 * account and compares the two payloads field for field. Everything runs in a
 * transaction that is rolled back — no user or token is left behind.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GoogleSignInParityTest {

    private static final String PASSWORD = "Parity#Secret123";

    @Autowired MockMvc mvc;
    @Autowired Oauth2SuccessHandler googleHandler;
    @Autowired AuthUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JsonMapper json;

    private AuthUserEntity account(boolean platformAdmin) {
        Instant now = Instant.now();
        return users.save(AuthUserEntity.builder()
                .name("Parity Test")
                .email("google-parity-" + UUID.randomUUID() + "@example.test")
                .company("Parity Co")
                .password(encoder.encode(PASSWORD))
                .provider(LoginProvider.LOCAL)
                .isEnable(true)
                .emailVerified(true)
                .isAdmin(false)
                .platformAdmin(platformAdmin)
                .createAt(now)
                .updateAt(now)
                .build());
    }

    private JsonNode passwordSignIn(String email) throws Exception {
        String body = mvc.perform(post("/auth/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("userDto");
    }

    private JsonNode googleSignIn(String email) throws Exception {
        var principal = new DefaultOAuth2User(List.of(),
                Map.of("sub", "google-123", "email", email, "name", "Parity Test"), "sub");
        var token = new OAuth2AuthenticationToken(principal, List.of(), "google");
        var response = new MockHttpServletResponse();
        googleHandler.onAuthenticationSuccess(new MockHttpServletRequest(), response, token);

        String redirect = response.getRedirectedUrl();
        assertNotNull(redirect, "the handler must send the browser back to the panel");
        assertTrue(redirect.contains("/oauth/callback#access_token="), redirect);
        String fragment = redirect.substring(redirect.indexOf('#') + 1);
        String userB64 = Arrays.stream(fragment.split("&"))
                .filter(part -> part.startsWith("user="))
                .findFirst().orElseThrow().substring("user=".length());
        return json.readTree(new String(Base64.getUrlDecoder().decode(userB64), StandardCharsets.UTF_8));
    }

    @Test
    void aGoogleSignInCarriesExactlyTheUserAPasswordSignInDoes() throws Exception {
        AuthUserEntity account = account(true);
        JsonNode password = passwordSignIn(account.getEmail());
        JsonNode google = googleSignIn(account.getEmail());

        assertEquals(password, google, "every field, same names, same values");
        assertTrue(google.get("platformAdmin").asBoolean(), "the Platform console stays visible");
        assertTrue(google.has("roles"), "role-gated pages see the same roles");
    }

    @Test
    void anOrdinaryAccountIsNotAPlatformAdminEitherWay() throws Exception {
        AuthUserEntity account = account(false);
        JsonNode google = googleSignIn(account.getEmail());
        assertEquals(passwordSignIn(account.getEmail()), google);
        assertFalse(google.get("platformAdmin").asBoolean());
    }

    @Test
    void thePasswordNeverTravelsInTheRedirect() throws Exception {
        AuthUserEntity account = account(true);
        JsonNode google = googleSignIn(account.getEmail());
        assertFalse(google.has("password"), google.toString());
    }

    @Test
    void aFirstGoogleSignInCreatesAnOrdinaryOwnerAccount() throws Exception {
        String email = "google-new-" + UUID.randomUUID() + "@example.test";
        JsonNode google = googleSignIn(email);
        assertEquals(email, google.get("email").asText());
        assertFalse(google.path("platformAdmin").asBoolean(false), "never a platform admin by signing up");
        assertFalse(google.get("admin").asBoolean());
        assertTrue(users.findByEmail(email).isPresent());
    }
}
