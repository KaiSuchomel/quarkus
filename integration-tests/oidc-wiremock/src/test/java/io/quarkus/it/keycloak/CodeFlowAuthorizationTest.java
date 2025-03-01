package io.quarkus.it.keycloak;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.gargoylesoftware.htmlunit.SilentCssErrorHandler;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.util.Cookie;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import io.quarkus.oidc.common.runtime.OidcCommonUtils;
import io.quarkus.oidc.runtime.OidcUtils;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWireMock;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;

@QuarkusTest
@QuarkusTestResource(OidcWiremockTestResource.class)
public class CodeFlowAuthorizationTest {

    @OidcWireMock
    WireMockServer wireMockServer;

    @BeforeAll
    public static void clearCache() {
        // clear token cache to make tests idempotent as we experienced failures
        // on Windows when BearerTokenAuthorizationTest run before CodeFlowAuthorizationTest
        RestAssured
                .given()
                .get("http://localhost:8081/clear-token-cache")
                .then()
                .statusCode(204);
    }

    @Test
    public void testCodeFlow() throws IOException {
        defineCodeFlowLogoutStub();
        try (final WebClient webClient = createWebClient()) {
            webClient.getOptions().setRedirectEnabled(true);
            HtmlPage page = webClient.getPage("http://localhost:8081/code-flow");

            HtmlForm form = page.getFormByName("form");
            form.getInputByName("username").type("alice");
            form.getInputByName("password").type("alice");

            page = form.getInputByValue("login").click();

            assertEquals("alice, cache size: 0", page.getBody().asNormalizedText());
            assertNotNull(getSessionCookie(webClient, "code-flow"));

            page = webClient.getPage("http://localhost:8081/code-flow/logout");
            assertEquals("Welcome, clientId: quarkus-web-app", page.getBody().asNormalizedText());
            assertNull(getSessionCookie(webClient, "code-flow"));
            // Clear the post logout cookie
            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testCodeFlowEncryptedIdTokenJwk() throws IOException {
        doTestCodeFlowEncryptedIdToken("code-flow-encrypted-id-token-jwk");
    }

    @Test
    public void testCodeFlowEncryptedIdTokenPem() throws IOException {
        doTestCodeFlowEncryptedIdToken("code-flow-encrypted-id-token-pem");
    }

    private void doTestCodeFlowEncryptedIdToken(String tenant) throws IOException {
        try (final WebClient webClient = createWebClient()) {
            webClient.getOptions().setRedirectEnabled(true);
            HtmlPage page = webClient.getPage("http://localhost:8081/code-flow-encrypted-id-token/" + tenant);

            HtmlForm form = page.getFormByName("form");
            form.getInputByName("username").type("alice");
            form.getInputByName("password").type("alice");

            page = form.getInputByValue("login").click();

            assertEquals("user: alice", page.getBody().asNormalizedText());
            Cookie sessionCookie = getSessionCookie(webClient, tenant);
            assertNotNull(sessionCookie);
            // default session cookie format: "idtoken|accesstoken|refreshtoken"
            assertTrue(OidcUtils.isEncryptedToken(sessionCookie.getValue().split("\\|")[0]));

            // repeat the call with the session cookie containing the encrypted id token
            page = webClient.getPage("http://localhost:8081/code-flow-encrypted-id-token/" + tenant);
            assertEquals("user: alice", page.getBody().asNormalizedText());

            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testCodeFlowFormPostAndBackChannelLogout() throws IOException {
        defineCodeFlowLogoutStub();
        try (final WebClient webClient = createWebClient()) {
            webClient.getOptions().setRedirectEnabled(true);
            HtmlPage page = webClient.getPage("http://localhost:8081/code-flow-form-post");

            HtmlForm form = page.getFormByName("form");
            form.getInputByName("username").type("alice");
            form.getInputByName("password").type("alice");

            page = form.getInputByValue("login").click();

            assertEquals("alice", page.getBody().asNormalizedText());

            assertNotNull(getSessionCookie(webClient, "code-flow-form-post"));

            page = webClient.getPage("http://localhost:8081/code-flow-form-post");
            assertEquals("alice", page.getBody().asNormalizedText());

            // Session is still active
            assertNotNull(getSessionCookie(webClient, "code-flow-form-post"));

            // request a back channel logout
            RestAssured.given()
                    .when().contentType(ContentType.URLENC).body("logout_token=" + OidcWiremockTestResource.getLogoutToken())
                    .post("/back-channel-logout")
                    .then()
                    .statusCode(200);

            // Confirm 302 is returned and the session cookie is null
            webClient.getOptions().setRedirectEnabled(false);
            WebResponse webResponse = webClient
                    .loadWebResponse(new WebRequest(URI.create("http://localhost:8081/code-flow-form-post").toURL()));
            assertEquals(302, webResponse.getStatusCode());

            assertNull(getSessionCookie(webClient, "code-flow-form-post"));

            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testCodeFlowFormPostAndFrontChannelLogout() throws Exception {
        defineCodeFlowLogoutStub();
        try (final WebClient webClient = createWebClient()) {
            webClient.getOptions().setRedirectEnabled(true);
            HtmlPage page = webClient.getPage("http://localhost:8081/code-flow-form-post");

            HtmlForm form = page.getFormByName("form");
            form.getInputByName("username").type("alice");
            form.getInputByName("password").type("alice");

            page = form.getInputByValue("login").click();

            assertEquals("alice", page.getBody().asNormalizedText());

            assertNotNull(getSessionCookie(webClient, "code-flow-form-post"));

            page = webClient.getPage("http://localhost:8081/code-flow-form-post");
            assertEquals("alice", page.getBody().asNormalizedText());

            // Session is still active
            JsonObject idTokenClaims = decryptIdToken(webClient, "code-flow-form-post");

            webClient.getOptions().setRedirectEnabled(false);

            // Confirm 302 is returned and the session cookie is null when the frontchannel logout URL is called
            URL frontchannelUrl = URI.create("http://localhost:8081/code-flow-form-post/front-channel-logout"
                    + "?sid=" + idTokenClaims.getString("sid") + "&iss="
                    + OidcCommonUtils.urlEncode(idTokenClaims.getString("iss"))).toURL();
            WebResponse webResponse = webClient.loadWebResponse(new WebRequest(frontchannelUrl));
            assertEquals(302, webResponse.getStatusCode());

            assertNull(getSessionCookie(webClient, "code-flow-form-post"));

            // remove the state cookie for Quarkus not to treat the next call as an expected redirect from OIDC
            webClient.getCookieManager().clearCookies();

            // Confirm 302 is returned and the session cookie is null when the endpoint is called
            webResponse = webClient
                    .loadWebResponse(new WebRequest(URI.create("http://localhost:8081/code-flow-form-post").toURL()));
            assertEquals(302, webResponse.getStatusCode());

            assertNull(getSessionCookie(webClient, "code-flow-form-post"));

            webClient.getCookieManager().clearCookies();
        }
    }

    @Test
    public void testCodeFlowUserInfo() throws Exception {
        defineCodeFlowAuthorizationOauth2TokenStub();

        doTestCodeFlowUserInfo("code-flow-user-info-only", 300);
        doTestCodeFlowUserInfo("code-flow-user-info-github", 360);
        doTestCodeFlowUserInfo("code-flow-user-info-dynamic-github", 301);

        doTestCodeFlowUserInfoCashedInIdToken();
    }

    @Test
    public void testCodeFlowTokenIntrospection() throws Exception {
        defineCodeFlowTokenIntrospectionStub();
        try (final WebClient webClient = createWebClient()) {
            webClient.getOptions().setRedirectEnabled(true);
            HtmlPage page = webClient.getPage("http://localhost:8081/code-flow-token-introspection");

            HtmlForm form = page.getFormByName("form");
            form.getInputByName("username").type("alice");
            form.getInputByName("password").type("alice");

            page = form.getInputByValue("login").click();

            assertEquals("alice", page.getBody().asNormalizedText());

            // refresh
            Thread.sleep(3000);
            page = webClient.getPage("http://localhost:8081/code-flow-token-introspection");
            assertEquals("admin", page.getBody().asNormalizedText());

            webClient.getCookieManager().clearCookies();
        }
    }

    private void doTestCodeFlowUserInfo(String tenantId, long internalIdTokenLifetime) throws Exception {
        try (final WebClient webClient = createWebClient()) {
            webClient.getOptions().setRedirectEnabled(true);
            HtmlPage page = webClient.getPage("http://localhost:8081/" + tenantId);

            HtmlForm form = page.getFormByName("form");
            form.getInputByName("username").type("alice");
            form.getInputByName("password").type("alice");

            page = form.getInputByValue("login").click();

            assertEquals("alice:alice:alice, cache size: 1", page.getBody().asNormalizedText());

            JsonObject idTokenClaims = decryptIdToken(webClient, tenantId);
            assertNull(idTokenClaims.getJsonObject(OidcUtils.USER_INFO_ATTRIBUTE));
            long issuedAt = idTokenClaims.getLong("iat");
            long expiresAt = idTokenClaims.getLong("exp");
            assertEquals(internalIdTokenLifetime, expiresAt - issuedAt);

            webClient.getCookieManager().clearCookies();
        }
    }

    private JsonObject decryptIdToken(WebClient webClient, String tenantId) throws Exception {
        Cookie sessionCookie = getSessionCookie(webClient, tenantId);
        assertNotNull(sessionCookie);

        SecretKey key = new SecretKeySpec(OidcUtils
                .getSha256Digest("AyM1SysPpbyDfgZld3umj1qzKObwVMkoqQ-EstJQLr_T-1qS0gZH75aKtMN3Yj0iPS4hcgUuTwjAzZr1Z9CAow"
                        .getBytes(StandardCharsets.UTF_8)),
                "AES");

        String decryptedSessionCookie = OidcUtils.decryptString(sessionCookie.getValue(), key);

        String encodedIdToken = decryptedSessionCookie.split("\\|")[0];

        return OidcUtils.decodeJwtContent(encodedIdToken);
    }

    private void doTestCodeFlowUserInfoCashedInIdToken() throws Exception {
        try (final WebClient webClient = createWebClient()) {
            webClient.getOptions().setRedirectEnabled(true);
            HtmlPage page = webClient.getPage("http://localhost:8081/code-flow-user-info-github-cached-in-idtoken");

            HtmlForm form = page.getFormByName("form");
            form.getInputByName("username").type("alice");
            form.getInputByName("password").type("alice");

            page = form.getInputByValue("login").click();

            assertEquals("alice:alice:alice, cache size: 0", page.getBody().asNormalizedText());

            JsonObject idTokenClaims = decryptIdToken(webClient, "code-flow-user-info-github-cached-in-idtoken");
            assertNotNull(idTokenClaims.getJsonObject(OidcUtils.USER_INFO_ATTRIBUTE));

            // refresh
            Thread.sleep(3000);
            page = webClient.getPage("http://localhost:8081/code-flow-user-info-github-cached-in-idtoken");
            assertEquals("alice:alice:bob, cache size: 0", page.getBody().asNormalizedText());

            webClient.getCookieManager().clearCookies();
        }
    }

    private WebClient createWebClient() {
        WebClient webClient = new WebClient();
        webClient.setCssErrorHandler(new SilentCssErrorHandler());
        return webClient;
    }

    private void defineCodeFlowAuthorizationOauth2TokenStub() {
        wireMockServer
                .stubFor(WireMock.post("/auth/realms/quarkus/access_token")
                        .withHeader("X-Custom", matching("XCustomHeaderValue"))
                        .withRequestBody(containing("extra-param=extra-param-value"))
                        .withRequestBody(containing("authorization_code"))
                        .willReturn(WireMock.aResponse()
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\n" +
                                        "  \"access_token\": \""
                                        + OidcWiremockTestResource.getAccessToken("alice", Set.of()) + "\","
                                        + "  \"refresh_token\": \"refresh1234\""
                                        + "}")));
        wireMockServer
                .stubFor(WireMock.post("/auth/realms/quarkus/access_token")
                        .withRequestBody(containing("refresh_token=refresh1234"))
                        .willReturn(WireMock.aResponse()
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\n" +
                                        "  \"access_token\": \""
                                        + OidcWiremockTestResource.getAccessToken("bob", Set.of()) + "\""
                                        + "}")));

    }

    private void defineCodeFlowTokenIntrospectionStub() {
        wireMockServer
                .stubFor(WireMock.post("/auth/realms/quarkus/access_token")
                        .withHeader("X-Custom", matching("XTokenIntrospection"))
                        .withRequestBody(containing("authorization_code"))
                        .willReturn(WireMock.aResponse()
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\n" +
                                        "  \"access_token\": \"alice\","
                                        + "  \"refresh_token\": \"refresh5678\""
                                        + "}")));

        wireMockServer
                .stubFor(WireMock.post("/auth/realms/quarkus/access_token")
                        .withRequestBody(containing("refresh_token=refresh5678"))
                        .willReturn(WireMock.aResponse()
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\n" +
                                        "  \"access_token\": \"admin\""
                                        + "}")));
    }

    private void defineCodeFlowLogoutStub() {
        wireMockServer.stubFor(
                get(urlPathMatching("/auth/realms/quarkus/protocol/openid-connect/end-session"))
                        .willReturn(aResponse()
                                .withHeader("Location",
                                        "{{request.query.returnTo}}?clientId={{request.query.client_id}}")
                                .withStatus(302)
                                .withTransformers("response-template")));
    }

    private Cookie getSessionCookie(WebClient webClient, String tenantId) {
        return webClient.getCookieManager().getCookie("q_session" + (tenantId == null ? "" : "_" + tenantId));
    }
}
