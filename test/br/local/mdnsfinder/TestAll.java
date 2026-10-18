package br.local.mdnsfinder;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Plain-Java tests (no framework). Run: java -cp <classes> br.local.mdnsfinder.TestAll */
public class TestAll {

    private static int checks = 0;
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        testNormalizeHost();
        testParsePort();
        testEncodeName();
        testBuildQuery();
        testParseResponse();
        testParseIgnoresGarbage();
        testResolveViaLoopback();
        testResolveViaTimeout();
        testBrowserUrl();
        testMessages();
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ---- input handling -------------------------------------------------

    static void testNormalizeHost() {
        eq("ender3.local", Mdns.normalizeHost("ender3"));
        eq("ender3.local", Mdns.normalizeHost("  Ender3.LOCAL  "));
        eq("ender3.local", Mdns.normalizeHost("ender3.local."));
        eq("ender3.local", Mdns.normalizeHost("http://ender3.local/"));
        eq("ender3.local", Mdns.normalizeHost("http://ender3.local:8080/webcam?x=1"));
        eq("ender3.local", Mdns.normalizeHost("user@ender3.local"));
        eq("ender3.local", Mdns.normalizeHost("ender3:5000"));
        eq("", Mdns.normalizeHost(""));
        eq("", Mdns.normalizeHost("   "));
        eq("", Mdns.normalizeHost(null));
        eq("", Mdns.normalizeHost("http://"));
    }

    static void testParsePort() {
        eq(-1, Mdns.parsePort("ender3.local"));
        eq(8080, Mdns.parsePort("ender3.local:8080"));
        eq(5000, Mdns.parsePort("http://ender3:5000/"));
        eq(-1, Mdns.parsePort("ender3.local:"));
        eq(-1, Mdns.parsePort("ender3.local:abc"));
        eq(-1, Mdns.parsePort("ender3.local:0"));
        eq(-1, Mdns.parsePort("ender3.local:70000"));
        eq(-1, Mdns.parsePort(null));
    }

    static void testEncodeName() {
        byte[] n = Mdns.encodeName("ender3.local");
        byte[] expected = {6, 'e', 'n', 'd', 'e', 'r', '3', 5, 'l', 'o', 'c', 'a', 'l', 0};
        check("encodeName bytes", Arrays.equals(expected, n));
        throwsIae("empty label", "a..b");
        throwsIae("leading dot", ".local");
        throwsIae("trailing dot", "a.local.");
        throwsIae("empty", "");
        StringBuilder longLabel = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            longLabel.append('x');
        }
        throwsIae("64-byte label", longLabel + ".local");
        // 63 bytes is fine
        Mdns.encodeName(longLabel.substring(1) + ".local");
        check("63-byte label accepted", true);
    }

    // ---- packets --------------------------------------------------------

    static void testBuildQuery() {
        byte[] q = Mdns.buildQuery("ender3.local");
        // 12 header + 14 name + 4 (A) + 2 pointer + 4 (AAAA)
        eq(36, q.length);
        eq(0, q[2] & 0xFF);                 // flags: query
        eq(0, q[3] & 0xFF);
        eq(2, q[5] & 0xFF);                 // two questions
        eq(1, q[12 + 14 + 1] & 0xFF);       // type A
        eq(0x80, q[12 + 14 + 2] & 0xFF);    // QU bit set
        eq(1, q[12 + 14 + 3] & 0xFF);       // class IN
        eq(0xC0, q[30] & 0xFF);             // pointer to offset 12
        eq(0x0C, q[31] & 0xFF);
        eq(28, q[33] & 0xFF);               // type AAAA
        eq(0x80, q[34] & 0xFF);
        // a query is not a response
        eq(0, Mdns.parse(q, q.length, "ender3.local").size());
    }

    /** Builds a response with the given answers (name, type, rdata). */
    static byte[] response(boolean echoQuestion, Object[][] answers) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int[] header = {0, 0, 0x84, 0, 0, echoQuestion ? 1 : 0, 0, answers.length, 0, 0, 0, 0};
        for (int b : header) {
            o.write(b);
        }
        if (echoQuestion) {
            byte[] n = Mdns.encodeName("ender3.local");
            o.write(n, 0, n.length);
            o.write(0);
            o.write(1);
            o.write(0);
            o.write(1);
        }
        for (Object[] a : answers) {
            byte[] n = (a[0] instanceof String) ? Mdns.encodeName((String) a[0]) : (byte[]) a[0];
            o.write(n, 0, n.length);
            int type = (Integer) a[1];
            byte[] rdata = (byte[]) a[2];
            o.write(0);
            o.write(type);
            o.write(0x80); // cache-flush bit + class IN
            o.write(1);
            o.write(0);
            o.write(0);
            o.write(0);
            o.write(120);
            o.write(0);
            o.write(rdata.length);
            o.write(rdata, 0, rdata.length);
        }
        return o.toByteArray();
    }

    static void testParseResponse() throws Exception {
        byte[] v4 = {(byte) 192, (byte) 168, 1, 50};
        byte[] v6 = new byte[16];
        v6[0] = 0x20;
        v6[1] = 0x01;
        v6[15] = 1;

        byte[] r = response(false, new Object[][] {
            {"ender3.local", 1, v4},
            {"ender3.local", 28, v6},
        });
        List<InetAddress> got = Mdns.parse(r, r.length, "ender3.local");
        eq(2, got.size());
        eq("192.168.1.50", got.get(0).getHostAddress());
        eq("2001:0:0:0:0:0:0:1", got.get(1).getHostAddress());

        // case-insensitive name match
        got = Mdns.parse(r, r.length, "ENDER3.local");
        eq(2, got.size());

        // echoed question section is skipped
        r = response(true, new Object[][] {{"ender3.local", 1, v4}});
        got = Mdns.parse(r, r.length, "ender3.local");
        eq(1, got.size());

        // records for other hosts are ignored
        r = response(false, new Object[][] {
            {"other.local", 1, new byte[] {10, 0, 0, 9}},
            {"ender3.local", 1, v4},
        });
        got = Mdns.parse(r, r.length, "ender3.local");
        eq(1, got.size());
        eq("192.168.1.50", got.get(0).getHostAddress());

        // compressed owner name: answer name is a pointer to a name placed earlier
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int[] header = {0, 0, 0x84, 0, 0, 1, 0, 1, 0, 0, 0, 0};
        for (int b : header) {
            o.write(b);
        }
        byte[] n = Mdns.encodeName("ender3.local");
        o.write(n, 0, n.length);
        o.write(0);
        o.write(1);
        o.write(0);
        o.write(1);
        o.write(0xC0);
        o.write(0x0C);
        int[] rest = {0, 1, 0x80, 1, 0, 0, 0, 120, 0, 4, 10, 1, 2, 3};
        for (int b : rest) {
            o.write(b);
        }
        byte[] c = o.toByteArray();
        got = Mdns.parse(c, c.length, "ender3.local");
        eq(1, got.size());
        eq("10.1.2.3", got.get(0).getHostAddress());

        // wrong rdata length for an A record is ignored
        r = response(false, new Object[][] {{"ender3.local", 1, new byte[] {1, 2, 3}}});
        eq(0, Mdns.parse(r, r.length, "ender3.local").size());
    }

    static void testParseIgnoresGarbage() {
        eq(0, Mdns.parse(new byte[0], 0, "ender3.local").size());
        eq(0, Mdns.parse(new byte[5], 5, "ender3.local").size());
        byte[] ones = new byte[200];
        Arrays.fill(ones, (byte) 0xFF);
        eq(0, Mdns.parse(ones, ones.length, "ender3.local").size());

        // pointer loop must not hang or throw
        byte[] loop = new byte[30];
        loop[2] = (byte) 0x84;
        loop[7] = 1;
        loop[12] = (byte) 0xC0;
        loop[13] = 12;
        eq(0, Mdns.parse(loop, loop.length, "ender3.local").size());

        // every truncation of a valid response is survived
        byte[] r = response(true, new Object[][] {{"ender3.local", 1, new byte[] {1, 2, 3, 4}}});
        for (int len = 0; len <= r.length; len++) {
            Mdns.parse(r, len, "ender3.local");
        }
        check("truncations survived", true);
    }

    // ---- network over loopback -------------------------------------------

    /** A fake responder: waits for one query and replies with the given address. */
    static Thread responder(final DatagramSocket sock, final byte[] answerPacket, final int[] seen) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    byte[] buf = new byte[1500];
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    sock.receive(p);
                    seen[0] = p.getLength();
                    DatagramPacket reply = new DatagramPacket(answerPacket, answerPacket.length,
                            p.getSocketAddress());
                    sock.send(reply);
                } catch (Exception ignored) {
                    // socket closed at the end of the test
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    static void testResolveViaLoopback() throws Exception {
        DatagramSocket server = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        DatagramSocket client = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        try {
            byte[] answer = response(false, new Object[][] {
                {"ender3.local", 1, new byte[] {(byte) 192, (byte) 168, 0, 77}},
            });
            int[] seen = new int[1];
            responder(server, answer, seen);
            List<DatagramSocket> socks = new ArrayList<DatagramSocket>();
            socks.add(client);
            long t0 = System.currentTimeMillis();
            List<InetAddress> got = Mdns.resolveVia(socks,
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.getLocalPort()),
                    "ender3.local", 3, 1500);
            long took = System.currentTimeMillis() - t0;
            eq(1, got.size());
            eq("192.168.0.77", got.get(0).getHostAddress());
            eq(36, seen[0]); // the responder saw the 36-byte query
            check("returns soon after the answer (" + took + " ms)", took < 1000);
        } finally {
            server.close();
            client.close();
        }
    }

    static void testResolveViaTimeout() throws Exception {
        DatagramSocket silent = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        DatagramSocket client = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        try {
            List<DatagramSocket> socks = new ArrayList<DatagramSocket>();
            socks.add(client);
            long t0 = System.currentTimeMillis();
            List<InetAddress> got = Mdns.resolveVia(socks,
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), silent.getLocalPort()),
                    "ender3.local", 2, 200);
            long took = System.currentTimeMillis() - t0;
            eq(0, got.size());
            check("gives up after about rounds * roundMs (" + took + " ms)", took >= 350 && took < 1500);
            eq(0, Mdns.resolveVia(new ArrayList<DatagramSocket>(), null, "ender3.local", 2, 200).size());
        } finally {
            silent.close();
            client.close();
        }
    }

    // ---- browser URL -----------------------------------------------------

    static void testBrowserUrl() throws Exception {
        InetAddress v4 = InetAddress.getByName("192.168.1.50");
        InetAddress v6 = InetAddress.getByName("2001:db8::1");
        InetAddress ll = InetAddress.getByName("fe80::1");
        eq("http://192.168.1.50/", Mdns.browserUrl(v4, -1));
        eq("http://192.168.1.50/", Mdns.browserUrl(v4, 80));
        eq("http://192.168.1.50:5000/", Mdns.browserUrl(v4, 5000));
        eq("http://[2001:db8:0:0:0:0:0:1]/", Mdns.browserUrl(v6, -1));

        List<InetAddress> l = new ArrayList<InetAddress>();
        l.add(ll);
        check("link-local only: nothing for the browser", Mdns.bestForBrowser(l) == null);
        l.add(v6);
        check("global IPv6 beats link-local", Mdns.bestForBrowser(l) == v6);
        l.add(v4);
        check("IPv4 beats IPv6", Mdns.bestForBrowser(l) == v4);
        check("empty list", Mdns.bestForBrowser(new ArrayList<InetAddress>()) == null);
    }

    // ---- messages --------------------------------------------------------

    static void testMessages() {
        for (Msg m : Msg.values()) {
            check(m + " has English text", m.text(false).length() > 0);
            check(m + " has Portuguese text", m.text(true).length() > 0);
        }
        check("Portuguese differs from English", !Msg.FIND.text(true).equals(Msg.FIND.text(false)));
    }

    // ---- helpers ---------------------------------------------------------

    static void eq(Object expected, Object actual) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            failures++;
            System.out.println("FAIL: expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static void throwsIae(String what, String name) {
        checks++;
        try {
            Mdns.encodeName(name);
            failures++;
            System.out.println("FAIL: expected IllegalArgumentException for " + what);
        } catch (IllegalArgumentException expected) {
            // good
        }
    }
}
