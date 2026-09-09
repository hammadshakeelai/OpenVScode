package com.openvscode.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

import java.net.URI;

public class UrlMatchingTest {

    private boolean isMatchingServer(String pageUrl, String serverUrl) {
        if (pageUrl == null || serverUrl == null) return false;
        if (pageUrl.startsWith(serverUrl)) return true;
        try {
            URI pageUri = new URI(pageUrl);
            URI serverUri = new URI(serverUrl);
            String pageHost = pageUri.getHost();
            String serverHost = serverUri.getHost();
            int pagePort = pageUri.getPort() == -1 ? ("https".equalsIgnoreCase(pageUri.getScheme()) ? 443 : 80) : pageUri.getPort();
            int serverPort = serverUri.getPort() == -1 ? ("https".equalsIgnoreCase(serverUri.getScheme()) ? 443 : 80) : serverUri.getPort();

            if (("127.0.0.1".equals(serverHost) || "localhost".equalsIgnoreCase(serverHost)) &&
                    ("127.0.0.1".equals(pageHost) || "localhost".equalsIgnoreCase(pageHost))) {
                return pagePort == serverPort;
            }
            return pageHost != null && pageHost.equalsIgnoreCase(serverHost) && pagePort == serverPort;
        } catch (Exception e) {
            return pageUrl.startsWith(serverUrl);
        }
    }

    private String normalizeUrl(String inputUrl) {
        String trimmed = inputUrl.trim();
        if (trimmed.isEmpty()) return trimmed;
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://" + trimmed;
        }
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    @Test
    public void testLoopbackMatching() {
        assertTrue(isMatchingServer("http://127.0.0.1:8080/", "http://127.0.0.1:8080"));
        assertTrue(isMatchingServer("http://localhost:8080/", "http://127.0.0.1:8080"));
        assertTrue(isMatchingServer("http://127.0.0.1:8080/index.html", "http://localhost:8080"));
    }

    @Test
    public void testPortMismatch() {
        assertFalse(isMatchingServer("http://127.0.0.1:8081/", "http://127.0.0.1:8080"));
        assertFalse(isMatchingServer("http://localhost:9000/", "http://localhost:8080"));
    }

    @Test
    public void testRemoteHostMatching() {
        assertTrue(isMatchingServer("http://192.168.1.50:8080/", "http://192.168.1.50:8080"));
        assertTrue(isMatchingServer("http://192.168.1.50:8080/workspace", "http://192.168.1.50:8080"));
        assertFalse(isMatchingServer("http://192.168.1.99:8080/", "http://192.168.1.50:8080"));
    }

    @Test
    public void testUrlNormalization() {
        assertEquals("http://127.0.0.1:8080", normalizeUrl("127.0.0.1:8080"));
        assertEquals("http://127.0.0.1:8080", normalizeUrl("http://127.0.0.1:8080/"));
        assertEquals("https://my-ide.lan:8443", normalizeUrl("https://my-ide.lan:8443/"));
        assertEquals("http://10.0.2.2:8080", normalizeUrl("  10.0.2.2:8080/  "));
    }

    @Test
    public void testSpecialKeyCodeMapping() {
        assertEquals(27, getKeyCodeForSpecial("Escape"));
        assertEquals(9, getKeyCodeForSpecial("Tab"));
        assertEquals(37, getKeyCodeForSpecial("ArrowLeft"));
        assertEquals(38, getKeyCodeForSpecial("ArrowUp"));
        assertEquals(39, getKeyCodeForSpecial("ArrowRight"));
        assertEquals(40, getKeyCodeForSpecial("ArrowDown"));
        assertEquals(0, getKeyCodeForSpecial("Unknown"));
    }

    private int getKeyCodeForSpecial(String value) {
        switch (value) {
            case "Escape": return 27;
            case "Tab": return 9;
            case "ArrowLeft": return 37;
            case "ArrowUp": return 38;
            case "ArrowRight": return 39;
            case "ArrowDown": return 40;
            default: return 0;
        }
    }
}
