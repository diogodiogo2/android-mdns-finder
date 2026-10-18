package br.local.mdnsfinder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Minimal one-shot multicast DNS (RFC 6762) resolver.
 *
 * Android's system resolver does not answer ".local" names, so this class
 * builds the query itself: it asks for the A and AAAA records of a host name
 * and asks the responder to reply by unicast (QU bit), so no multicast group
 * membership is needed. Pure Java, no Android classes, so it can be unit
 * tested on a desktop JVM.
 */
public final class Mdns {

    public static final String GROUP = "224.0.0.251";
    public static final int PORT = 5353;

    private static final int TYPE_A = 1;
    private static final int TYPE_AAAA = 28;
    private static final int CLASS_IN = 1;
    private static final int QU_BIT = 0x8000;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private Mdns() {
    }

    // ------------------------------------------------------------------
    // User input handling
    // ------------------------------------------------------------------

    /**
     * Turns what the user typed into a host name: scheme, path, user info and
     * port are dropped, and ".local" is appended when the name has no dot.
     * Returns an empty string when there is nothing usable.
     */
    public static String normalizeHost(String input) {
        String s = authority(input);
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(0, colon);
        }
        s = s.trim().toLowerCase(Locale.ENGLISH);
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.length() == 0) {
            return "";
        }
        if (s.indexOf('.') < 0) {
            s = s + ".local";
        }
        return s;
    }

    /** Returns the port typed after the name ("ender3.local:8080"), or -1. */
    public static int parsePort(String input) {
        String s = authority(input);
        int colon = s.indexOf(':');
        if (colon < 0) {
            return -1;
        }
        String p = s.substring(colon + 1).trim();
        if (p.length() == 0 || p.length() > 5) {
            return -1;
        }
        for (int i = 0; i < p.length(); i++) {
            if (p.charAt(i) < '0' || p.charAt(i) > '9') {
                return -1;
            }
        }
        int v = Integer.parseInt(p);
        return (v >= 1 && v <= 65535) ? v : -1;
    }

    private static String authority(String input) {
        String s = input == null ? "" : input.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                s = s.substring(0, i);
                break;
            }
        }
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            s = s.substring(at + 1);
        }
        return s;
    }

    // ------------------------------------------------------------------
    // Packet building and parsing
    // ------------------------------------------------------------------

    /** Encodes a dotted name as DNS labels. Throws IllegalArgumentException on bad names. */
    static byte[] encodeName(String host) {
        if (host == null || host.length() == 0) {
            throw new IllegalArgumentException("empty name");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int start = 0;
        while (true) {
            int dot = host.indexOf('.', start);
            String label = dot < 0 ? host.substring(start) : host.substring(start, dot);
            byte[] bytes = label.getBytes(UTF8);
            if (bytes.length == 0 || bytes.length > 63) {
                throw new IllegalArgumentException("bad label in " + host);
            }
            out.write(bytes.length);
            out.write(bytes, 0, bytes.length);
            if (dot < 0) {
                break;
            }
            start = dot + 1;
        }
        out.write(0);
        byte[] result = out.toByteArray();
        if (result.length > 255) {
            throw new IllegalArgumentException("name too long");
        }
        return result;
    }

    /** One packet with two questions: A and AAAA for the host, both with the QU bit. */
    public static byte[] buildQuery(String host) {
        byte[] name = encodeName(host);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Header: id 0, flags 0, QDCOUNT 2, no other records.
        int[] header = {0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0};
        for (int i = 0; i < header.length; i++) {
            out.write(header[i]);
        }
        out.write(name, 0, name.length);
        writeU16(out, TYPE_A);
        writeU16(out, CLASS_IN | QU_BIT);
        out.write(0xC0); // pointer to the name at offset 12
        out.write(0x0C);
        writeU16(out, TYPE_AAAA);
        writeU16(out, CLASS_IN | QU_BIT);
        return out.toByteArray();
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    /**
     * Extracts the A / AAAA addresses that a response gives for the host.
     * Malformed or unrelated packets yield an empty (or partial) list.
     */
    public static List<InetAddress> parse(byte[] buf, int len, String host) {
        List<InetAddress> result = new ArrayList<InetAddress>();
        if (len < 12 || len > buf.length) {
            return result;
        }
        try {
            int flags = u16(buf, 2);
            if ((flags & 0x8000) == 0) {
                return result; // a query, not a response
            }
            int questions = u16(buf, 4);
            int records = u16(buf, 6) + u16(buf, 8) + u16(buf, 10);
            int pos = 12;
            for (int i = 0; i < questions; i++) {
                pos = skipName(buf, len, pos) + 4;
            }
            for (int i = 0; i < records; i++) {
                String name = readName(buf, len, pos);
                pos = skipName(buf, len, pos);
                if (pos + 10 > len) {
                    break;
                }
                int type = u16(buf, pos);
                int cls = u16(buf, pos + 2) & 0x7FFF;
                int rdlen = u16(buf, pos + 8);
                pos += 10;
                if (pos + rdlen > len) {
                    break;
                }
                if (cls == CLASS_IN && name.equalsIgnoreCase(host)) {
                    if (type == TYPE_A && rdlen == 4 || type == TYPE_AAAA && rdlen == 16) {
                        byte[] raw = new byte[rdlen];
                        System.arraycopy(buf, pos, raw, 0, rdlen);
                        try {
                            result.add(InetAddress.getByAddress(raw));
                        } catch (UnknownHostException ignored) {
                            // cannot happen for 4 or 16 bytes
                        }
                    }
                }
                pos += rdlen;
            }
        } catch (RuntimeException malformed) {
            // keep whatever was parsed before the damage
        }
        return result;
    }

    private static int u16(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    private static int skipName(byte[] buf, int len, int pos) {
        while (true) {
            if (pos >= len) {
                throw new IllegalArgumentException("truncated name");
            }
            int b = buf[pos] & 0xFF;
            if (b == 0) {
                return pos + 1;
            }
            if ((b & 0xC0) == 0xC0) {
                return pos + 2;
            }
            if ((b & 0xC0) != 0) {
                throw new IllegalArgumentException("bad label");
            }
            pos += 1 + b;
        }
    }

    private static String readName(byte[] buf, int len, int pos) {
        StringBuilder sb = new StringBuilder();
        int hops = 0;
        while (true) {
            if (pos >= len) {
                throw new IllegalArgumentException("truncated name");
            }
            int b = buf[pos] & 0xFF;
            if (b == 0) {
                break;
            }
            if ((b & 0xC0) == 0xC0) {
                if (pos + 1 >= len || ++hops > 20) {
                    throw new IllegalArgumentException("bad pointer");
                }
                pos = ((b & 0x3F) << 8) | (buf[pos + 1] & 0xFF);
                continue;
            }
            if ((b & 0xC0) != 0 || pos + 1 + b > len) {
                throw new IllegalArgumentException("bad label");
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(new String(buf, pos + 1, b, UTF8));
            pos += 1 + b;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Network
    // ------------------------------------------------------------------

    /**
     * Sends the query on every given socket and collects answers. Stops after
     * the first round that produced an answer (plus a short settle time so
     * the AAAA reply can arrive too). Blocks for at most rounds * roundMs.
     */
    public static List<InetAddress> resolveVia(List<? extends DatagramSocket> sockets,
            SocketAddress target, String host, int rounds, int roundMs) {
        Set<InetAddress> found = new LinkedHashSet<InetAddress>();
        List<DatagramSocket> live = new ArrayList<DatagramSocket>(sockets);
        byte[] query = buildQuery(host);
        byte[] buf = new byte[9000];
        for (int r = 0; r < rounds && found.isEmpty() && !live.isEmpty(); r++) {
            for (DatagramSocket s : live) {
                try {
                    s.send(new DatagramPacket(query, query.length, target));
                } catch (IOException ignored) {
                    // one interface failing must not stop the others
                }
            }
            long end = System.currentTimeMillis() + roundMs;
            long settleEnd = 0;
            while (!live.isEmpty()) {
                long now = System.currentTimeMillis();
                if (now >= end || (settleEnd != 0 && now >= settleEnd)) {
                    break;
                }
                for (Iterator<DatagramSocket> it = live.iterator(); it.hasNext();) {
                    DatagramSocket s = it.next();
                    try {
                        s.setSoTimeout(50);
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        s.receive(p);
                        found.addAll(parse(p.getData(), p.getLength(), host));
                    } catch (SocketTimeoutException quiet) {
                        // nothing yet
                    } catch (IOException broken) {
                        it.remove();
                    }
                }
                if (!found.isEmpty() && settleEnd == 0) {
                    settleEnd = System.currentTimeMillis() + 150;
                }
            }
        }
        return ordered(found);
    }

    /** Resolves the host on every usable local network interface. */
    public static List<InetAddress> resolve(String host) throws IOException {
        List<InetAddress> locals = localAddresses();
        if (locals.isEmpty()) {
            throw new IOException("no network interface");
        }
        SocketAddress target = new InetSocketAddress(InetAddress.getByName(GROUP), PORT);
        List<DatagramSocket> sockets = new ArrayList<DatagramSocket>();
        try {
            for (InetAddress local : locals) {
                try {
                    MulticastSocket s = new MulticastSocket(new InetSocketAddress(local, 0));
                    sockets.add(s);
                    s.setTimeToLive(255); // mDNS requires TTL 255
                    s.setInterface(local);
                } catch (IOException skipThisInterface) {
                    // try the others
                }
            }
            return resolveVia(sockets, target, host, 3, 1500);
        } finally {
            for (DatagramSocket s : sockets) {
                s.close();
            }
        }
    }

    /** IPv4 addresses of the interfaces that are up and can do multicast (mobile data is skipped). */
    static List<InetAddress> localAddresses() throws IOException {
        List<InetAddress> strict = new ArrayList<InetAddress>();
        List<InetAddress> relaxed = new ArrayList<InetAddress>();
        Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
        while (all != null && all.hasMoreElements()) {
            NetworkInterface ni = all.nextElement();
            if (!ni.isUp() || ni.isLoopback()) {
                continue;
            }
            boolean multicast = ni.supportsMulticast() && !ni.isPointToPoint();
            Enumeration<InetAddress> addrs = ni.getInetAddresses();
            while (addrs.hasMoreElements()) {
                InetAddress a = addrs.nextElement();
                if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                    relaxed.add(a);
                    if (multicast) {
                        strict.add(a);
                    }
                }
            }
        }
        return strict.isEmpty() ? relaxed : strict;
    }

    private static List<InetAddress> ordered(Set<InetAddress> found) {
        List<InetAddress> out = new ArrayList<InetAddress>();
        for (InetAddress a : found) {
            if (a instanceof Inet4Address) {
                out.add(a);
            }
        }
        for (InetAddress a : found) {
            if (!(a instanceof Inet4Address)) {
                out.add(a);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Browser URL
    // ------------------------------------------------------------------

    /** First IPv4 address, else the first IPv6 address a browser can use; null when none. */
    public static InetAddress bestForBrowser(List<InetAddress> addresses) {
        for (InetAddress a : addresses) {
            if (a instanceof Inet4Address) {
                return a;
            }
        }
        for (InetAddress a : addresses) {
            if (a instanceof Inet6Address && !a.isLinkLocalAddress()) {
                return a;
            }
        }
        return null;
    }

    /** http://ADDRESS[:port]/ (the port is left out when it is -1 or 80). */
    public static String browserUrl(InetAddress address, int port) {
        String host = address.getHostAddress();
        if (address instanceof Inet6Address) {
            int scope = host.indexOf('%');
            if (scope >= 0) {
                host = host.substring(0, scope);
            }
            host = "[" + host + "]";
        }
        String p = (port > 0 && port != 80) ? ":" + port : "";
        return "http://" + host + p + "/";
    }
}
