package com.openvscode.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.List;

/**
 * Covers the address handling that decides whether the app can reach a server
 * and whether the loading overlay ever comes down.
 *
 * These call MainActivity's real methods. An earlier version of this file kept
 * private copies of the logic, which meant it could pass while the shipped app
 * was broken.
 */
public class UrlMatchingTest {

    // ---- candidateUrls: accept anything a user can type -------------------

    @Test
    public void bareLocalAddressPrefersHttp() {
        List<String> c = MainActivity.candidateUrls("192.168.1.5:8100");
        assertEquals(2, c.size());
        assertEquals("http://192.168.1.5:8100", c.get(0));
        assertEquals("https://192.168.1.5:8100", c.get(1));
    }

    @Test
    public void barePublicHostPrefersHttps() {
        List<String> c = MainActivity.candidateUrls("ide.example.com");
        assertEquals("https://ide.example.com", c.get(0));
        assertEquals("http://ide.example.com", c.get(1));
    }

    @Test
    public void explicitSchemeIsHonouredAndNotSecondGuessed() {
        List<String> c = MainActivity.candidateUrls("https://abc.ngrok.io");
        assertEquals(1, c.size());
        assertEquals("https://abc.ngrok.io", c.get(0));
    }

    @Test
    public void trailingSlashesAndWhitespaceAreStripped() {
        assertEquals("http://127.0.0.1:8080",
                MainActivity.candidateUrls("  http://127.0.0.1:8080//  ").get(0));
        assertEquals("http://10.0.2.2:8080",
                MainActivity.candidateUrls(" 10.0.2.2:8080/ ").get(0));
    }

    @Test
    public void queryStringSurvivesSoConnectionTokensWork() {
        assertEquals("http://10.0.0.4:8443/?tkn=secret",
                MainActivity.candidateUrls("10.0.0.4:8443/?tkn=secret").get(0));
    }

    @Test
    public void emptyInputYieldsNoCandidates() {
        assertTrue(MainActivity.candidateUrls(null).isEmpty());
        assertTrue(MainActivity.candidateUrls("   ").isEmpty());
        assertTrue(MainActivity.candidateUrls("http://").isEmpty());
    }

    // ---- hostOf ----------------------------------------------------------

    @Test
    public void hostIsExtractedFromAnyAuthorityShape() {
        assertEquals("1.2.3.4", MainActivity.hostOf("1.2.3.4:8100/path?q=1"));
        assertEquals("example.com", MainActivity.hostOf("example.com"));
        assertEquals("host", MainActivity.hostOf("user@host:80"));
        assertEquals("[::1]", MainActivity.hostOf("[::1]:8080"));
        assertEquals("", MainActivity.hostOf(null));
    }

    // ---- isLocalAddress --------------------------------------------------

    @Test
    public void privateRangesCountAsLocal() {
        assertTrue(MainActivity.isLocalAddress("192.168.1.5"));
        assertTrue(MainActivity.isLocalAddress("10.0.0.4"));
        assertTrue(MainActivity.isLocalAddress("172.16.0.1"));
        assertTrue(MainActivity.isLocalAddress("172.31.255.254"));
        assertTrue(MainActivity.isLocalAddress("127.0.0.1"));
        assertTrue(MainActivity.isLocalAddress("localhost"));
        assertTrue(MainActivity.isLocalAddress("my-box.local"));
        assertTrue(MainActivity.isLocalAddress("workstation"));
    }

    @Test
    public void publicAddressesAreNotLocal() {
        assertFalse(MainActivity.isLocalAddress("8.8.8.8"));
        assertFalse(MainActivity.isLocalAddress("ide.example.com"));
        // 172.32 is outside the RFC1918 block; the boundary is worth pinning.
        assertFalse(MainActivity.isLocalAddress("172.32.0.1"));
        assertFalse(MainActivity.isLocalAddress("172.15.0.1"));
        assertFalse(MainActivity.isLocalAddress(""));
        assertFalse(MainActivity.isLocalAddress(null));
    }

    // ---- subnetPrefixOf --------------------------------------------------

    @Test
    public void subnetPrefixIsDerivedForScanning() {
        assertEquals("192.168.18.", MainActivity.subnetPrefixOf("192.168.18.56"));
        assertEquals("10.0.0.", MainActivity.subnetPrefixOf("10.0.0.7"));
        assertNull(MainActivity.subnetPrefixOf(null));
        assertNull(MainActivity.subnetPrefixOf("nonsense"));
    }

    // ---- isMatchingServer: the overlay-dismissal fast path ----------------

    @Test
    public void loopbackAliasesMatch() {
        assertTrue(MainActivity.isMatchingServer("http://127.0.0.1:8080/", "http://127.0.0.1:8080"));
        assertTrue(MainActivity.isMatchingServer("http://localhost:8080/", "http://127.0.0.1:8080"));
        assertTrue(MainActivity.isMatchingServer("http://127.0.0.1:8080/index.html", "http://localhost:8080"));
    }

    @Test
    public void differentPortDoesNotMatch() {
        assertFalse(MainActivity.isMatchingServer("http://127.0.0.1:8081/", "http://127.0.0.1:8080"));
        assertFalse(MainActivity.isMatchingServer("http://localhost:9000/", "http://localhost:8080"));
    }

    @Test
    public void lanHostMatchesRegardlessOfPath() {
        assertTrue(MainActivity.isMatchingServer("http://192.168.1.50:8080/", "http://192.168.1.50:8080"));
        assertTrue(MainActivity.isMatchingServer("http://192.168.1.50:8080/workspace", "http://192.168.1.50:8080"));
        assertFalse(MainActivity.isMatchingServer("http://192.168.1.99:8080/", "http://192.168.1.50:8080"));
    }

    @Test
    public void queryStringDoesNotBreakMatching() {
        // code serve-web hands its token off to a cookie and redirects to "/",
        // so the page URL loses the query the user typed.
        assertTrue(MainActivity.isMatchingServer(
                "http://192.168.18.56:8100/", "http://192.168.18.56:8100/?tkn=abc"));
    }
}
