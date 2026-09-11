package com.openvscode.mobile;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure address policy, also used by the WebView navigation boundary. */
final class ServerAddress {
    private ServerAddress() {}
    static List<String> candidates(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) return result;
        String value = raw.trim();
        if (value.isEmpty() || value.matches(".*\\s+.*")) return result;
        boolean scheme=value.contains("://");
        while(value.endsWith("/")) value=value.substring(0,value.length()-1);
        if (value.isEmpty()) return result;
        String first=scheme ? value : (isLocal(hostOf(value)) ? "http://" : "https://")+value;
        try {
            URI uri = new URI(first);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost()==null || uri.getHost().isEmpty() || uri.getPort()>65535 || uri.getPort() < -1) return result;
            result.add(first);
            if(!scheme) result.add((first.startsWith("https:") ? "http://" : "https://")+value);
        } catch (Exception ignored) {}
        return result;
    }
    static String hostOf(String value) {
        if(value==null)return "";
        try { String host=new URI("http://"+value).getHost(); return host==null?"":host; }
        catch(Exception ignored) { return ""; }
    }
    static boolean isLocal(String host) {
        if(host==null || host.isEmpty())return false;
        String h=host.toLowerCase(Locale.ROOT);
        if(h.equals("localhost") || h.equals("::1") || h.equals("[::1]"))return true;
        if(h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home"))return true;
        if(h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254."))return true;
        if(h.startsWith("172.")) { try { int part=Integer.parseInt(h.split("\\.")[1]); return part>=16 && part<=31; } catch(Exception ignored) {} }
        return !h.contains(".");
    }
    static boolean sameOrigin(String page,String server) {
        try {
            URI a=new URI(page), b=new URI(server);
            String ah=normalizedHost(a), bh=normalizedHost(b);
            return !ah.isEmpty() && ah.equals(bh) && port(a)==port(b)
                && a.getScheme()!=null && a.getScheme().equalsIgnoreCase(b.getScheme());
        } catch(Exception ignored) { return false; }
    }
    private static String normalizedHost(URI uri) {
        String host=uri.getHost(); if(host==null)return "";
        return host.equalsIgnoreCase("localhost")?"127.0.0.1":host.toLowerCase(Locale.ROOT);
    }
    private static int port(URI uri) { return uri.getPort()!=-1?uri.getPort():"https".equalsIgnoreCase(uri.getScheme())?443:80; }
}
