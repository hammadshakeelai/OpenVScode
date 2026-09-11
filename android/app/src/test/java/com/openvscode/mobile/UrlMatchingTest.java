package com.openvscode.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.List;

/**
 * Covers the address handling that decides whether the app can reach a server
 * and whether the loading overlay ever comes down.
 *
 * These call the production ServerAddress policy used by MainActivity. An earlier version of this file kept
 * private copies of the logic, which meant it could pass while the shipped app
 * was broken.
 */
public class UrlMatchingTest {

    @Test public void unsafeOrMalformedSchemesAreRejected() {
        assertTrue(ServerAddress.candidates("javascript://example.com").isEmpty());
        assertTrue(ServerAddress.candidates("file:///sdcard/secrets").isEmpty());
        assertTrue(ServerAddress.candidates("http://").isEmpty());
        assertTrue(ServerAddress.candidates("http://example.com:99999").isEmpty());
        assertTrue(ServerAddress.candidates("not a hostname").isEmpty());
    }

    @Test public void hostAndPortPrefixesDoNotCrossTheNavigationBoundary() {
        assertFalse(ServerAddress.sameOrigin("http://example.com.evil.test/", "http://example.com"));
        assertFalse(ServerAddress.sameOrigin("http://localhost:80801/", "http://localhost:8080"));
        assertFalse(ServerAddress.sameOrigin("http://example.com/", "https://example.com"));
        assertFalse(ServerAddress.sameOrigin(null, "http://example.com"));
    }

    // ---- candidateUrls: accept anything a user can type -------------------

    @Test
    public void bareLocalAddressPrefersHttp() {
        List<String> c = ServerAddress.candidates("192.168.1.5:8100");
        assertEquals(2, c.size());
        assertEquals("http://192.168.1.5:8100", c.get(0));
        assertEquals("https://192.168.1.5:8100", c.get(1));
    }

    @Test
    public void barePublicHostPrefersHttps() {
        List<String> c = ServerAddress.candidates("ide.example.com");
        assertEquals("https://ide.example.com", c.get(0));
        assertEquals("http://ide.example.com", c.get(1));
    }

    @Test
    public void explicitSchemeIsHonouredAndNotSecondGuessed() {
        List<String> c = ServerAddress.candidates("https://abc.ngrok.io");
        assertEquals(1, c.size());
        assertEquals("https://abc.ngrok.io", c.get(0));
    }

    @Test
    public void trailingSlashesAndWhitespaceAreStripped() {
        assertEquals("http://127.0.0.1:8080",
                ServerAddress.candidates("  http://127.0.0.1:8080//  ").get(0));
        assertEquals("http://10.0.2.2:8080",
                ServerAddress.candidates(" 10.0.2.2:8080/ ").get(0));
    }

    @Test
    public void queryStringSurvivesSoConnectionTokensWork() {
        assertEquals("http://10.0.0.4:8443/?tkn=secret",
                ServerAddress.candidates("10.0.0.4:8443/?tkn=secret").get(0));
    }

    @Test
    public void emptyInputYieldsNoCandidates() {
        assertTrue(ServerAddress.candidates(null).isEmpty());
        assertTrue(ServerAddress.candidates("   ").isEmpty());
        assertTrue(ServerAddress.candidates("http://").isEmpty());
    }

    // ---- hostOf ----------------------------------------------------------

    @Test
    public void hostIsExtractedFromAnyAuthorityShape() {
        assertEquals("1.2.3.4", ServerAddress.hostOf("1.2.3.4:8100/path?q=1"));
        assertEquals("example.com", ServerAddress.hostOf("example.com"));
        assertEquals("host", ServerAddress.hostOf("user@host:80"));
        assertEquals("[::1]", ServerAddress.hostOf("[::1]:8080"));
        assertEquals("", ServerAddress.hostOf(null));
    }

    // ---- isLocalAddress --------------------------------------------------

    @Test
    public void privateRangesCountAsLocal() {
        assertTrue(ServerAddress.isLocal("192.168.1.5"));
        assertTrue(ServerAddress.isLocal("10.0.0.4"));
        assertTrue(ServerAddress.isLocal("172.16.0.1"));
        assertTrue(ServerAddress.isLocal("172.31.255.254"));
        assertTrue(ServerAddress.isLocal("127.0.0.1"));
        assertTrue(ServerAddress.isLocal("localhost"));
        assertTrue(ServerAddress.isLocal("my-box.local"));
        assertTrue(ServerAddress.isLocal("workstation"));
    }

    @Test
    public void publicAddressesAreNotLocal() {
        assertFalse(ServerAddress.isLocal("8.8.8.8"));
        assertFalse(ServerAddress.isLocal("ide.example.com"));
        // 172.32 is outside the RFC1918 block; the boundary is worth pinning.
        assertFalse(ServerAddress.isLocal("172.32.0.1"));
        assertFalse(ServerAddress.isLocal("172.15.0.1"));
        assertFalse(ServerAddress.isLocal(""));
        assertFalse(ServerAddress.isLocal(null));
    }

    // ---- isMatchingServer: the overlay-dismissal fast path ----------------

    @Test
    public void loopbackAliasesMatch() {
        assertTrue(ServerAddress.sameOrigin("http://127.0.0.1:8080/", "http://127.0.0.1:8080"));
        assertTrue(ServerAddress.sameOrigin("http://localhost:8080/", "http://127.0.0.1:8080"));
        assertTrue(ServerAddress.sameOrigin("http://127.0.0.1:8080/index.html", "http://localhost:8080"));
    }

    @Test
    public void differentPortDoesNotMatch() {
        assertFalse(ServerAddress.sameOrigin("http://127.0.0.1:8081/", "http://127.0.0.1:8080"));
        assertFalse(ServerAddress.sameOrigin("http://localhost:9000/", "http://localhost:8080"));
    }

    @Test
    public void lanHostMatchesRegardlessOfPath() {
        assertTrue(ServerAddress.sameOrigin("http://192.168.1.50:8080/", "http://192.168.1.50:8080"));
        assertTrue(ServerAddress.sameOrigin("http://192.168.1.50:8080/workspace", "http://192.168.1.50:8080"));
        assertFalse(ServerAddress.sameOrigin("http://192.168.1.99:8080/", "http://192.168.1.50:8080"));
    }

    @Test
    public void queryStringDoesNotBreakMatching() {
        // code serve-web hands its token off to a cookie and redirects to "/",
        // so the page URL loses the query the user typed.
        assertTrue(ServerAddress.sameOrigin(
                "http://192.168.18.56:8100/", "http://192.168.18.56:8100/?tkn=abc"));
    }
}
