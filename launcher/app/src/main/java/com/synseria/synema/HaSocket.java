package com.synseria.synema;

import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Home Assistant's WebSocket API, for what must arrive at once (a person at the door) rather than at
 * the next poll. A minimal RFC 6455 client (text frames, ping/pong, close) so the APK carries no HTTP
 * library: Home Assistant only exchanges small JSON messages. One connection on its own thread,
 * reconnecting with a growing delay (15 s at most) until close() is called.
 */
final class HaSocket {
    private static final String TAG = "TvHome.HaSocket";

    interface Listener {
        /** Called on the socket thread once authenticated: send subscriptions from here. */
        void onReady(HaSocket socket);

        /** Every message after authentication, on the socket thread. */
        void onMessage(JSONObject message);

        /** On the socket thread: the connection failed or dropped; it is tried again by itself. */
        default void onLost() {
        }
    }

    private final URI uri;
    /** Home Assistant's own API (auth messages, ids, pings) rather than a proxied socket (go2rtc). */
    private final boolean haApi;
    private final String token;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private volatile boolean closed;
    private volatile Socket socket;
    private OutputStream out;
    private DataInputStream input;
    private int nextId = 1;

    /** @param baseUrl the Home Assistant address, http(s)://host[:port] */
    HaSocket(String baseUrl, String token, Listener listener) {
        this(baseUrl, "/api/websocket", true, token, listener);
    }

    private HaSocket(String baseUrl, String path, boolean haApi, String token, Listener listener) {
        this.uri = URI.create(baseUrl.replaceAll("/+$", "").replaceFirst("^http", "ws") + path);
        this.haApi = haApi;
        this.token = token;
        this.listener = listener;
    }

    /**
     * A socket Home Assistant proxies to another service (the Frigate integration's go2rtc), signed in
     * with the token as a header: onReady once connected, every text message to onMessage, no pings.
     */
    static HaSocket proxied(String baseUrl, String pathAndQuery, String token, Listener listener) {
        return new HaSocket(baseUrl, pathAndQuery, false, token, listener);
    }

    void start() {
        Thread t = new Thread(this::loop, "ha-socket");
        t.setDaemon(true);
        t.start();
    }

    /** Safe from the main thread: closing a TLS socket writes to the network, so it happens off it. */
    void close() {
        closed = true;
        Socket s = socket;
        if (s != null) new Thread(() -> close(s), "ha-socket-close").start();
    }

    /** Sends a command with the next id and returns that id. */
    synchronized int send(JSONObject command) throws IOException {
        int id = nextId++;
        try {
            command.put("id", id);
        } catch (Exception e) {
            throw new IOException(e);
        }
        sendRaw(command.toString());
        return id;
    }

    private void loop() {
        long delay = 2_000;
        while (!closed) {
            try {
                connect();
                delay = 2_000;   // a session that got through the handshake resets the back-off
                read();
            } catch (Exception e) {
                if (!closed) {
                    Log.w(TAG, "connection lost: " + e);
                    listener.onLost();
                }
            }
            close(socket);
            if (closed) break;
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                return;
            }
            // Home Assistant back from a restart is seen within this: an alert or a state is not missed for long.
            delay = Math.min(delay * 2, 15_000);
        }
    }

    private void connect() throws IOException {
        boolean tls = "wss".equals(uri.getScheme());
        int port = uri.getPort() > 0 ? uri.getPort() : tls ? 443 : 80;
        Socket s;
        if (tls) {
            SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            // Before the handshake: a server that takes the connection but never answers (a restart
            // behind its ingress) would otherwise hold this thread for good, and nothing reconnects.
            ssl.setSoTimeout(30_000);
            ssl.connect(new InetSocketAddress(uri.getHost(), port), 8000);
            ssl.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(uri.getHost(), ssl.getSession())) {
                ssl.close();
                throw new IOException("certificate does not match " + uri.getHost());
            }
            s = ssl;
        } else {
            s = new Socket();
            s.connect(new InetSocketAddress(uri.getHost(), port), 8000);
        }
        s.setSoTimeout(30_000);   // read() pings on each silent 30 s and gives up after two unanswered
        socket = s;
        out = s.getOutputStream();
        byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
        String host = uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
        String path = uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
        String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nUpgrade: websocket\r\n"
                + (haApi ? "" : "Authorization: Bearer " + token + "\r\n")
                + "Connection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = new BufferedInputStream(s.getInputStream());
        String status = readLine(in);
        if (!status.startsWith("HTTP/1.1 101")) throw new IOException("handshake refused: " + status);
        while (!readLine(in).isEmpty()) {
            // headers: the server's accept key is not checked, TLS already authenticates the server
        }
        input = new DataInputStream(in);
    }

    private void read() throws Exception {
        if (!haApi) {
            readProxied();
            return;
        }
        boolean authed = false;
        int silent = 0;
        while (!closed) {
            String text;
            try {
                text = readFrame();
                silent = 0;
            } catch (java.net.SocketTimeoutException e) {
                // A timeout mid-frame would desynchronise the stream, but frames here are tiny and
                // arrive whole; the reconnect below covers the rare case.
                if (!authed || ++silent > 2) throw new IOException("no answer from Home Assistant");
                send(new JSONObject().put("type", "ping"));
                continue;
            }
            if (text == null) continue;   // control frame handled
            JSONObject msg = new JSONObject(text);
            String type = msg.optString("type");
            if ("auth_required".equals(type)) {
                sendRaw(new JSONObject().put("type", "auth").put("access_token", token).toString());
            } else if ("auth_ok".equals(type)) {
                authed = true;
                listener.onReady(this);
            } else if ("auth_invalid".equals(type)) {
                closed = true;
                Log.w(TAG, "token refused: " + msg.optString("message"));
                listener.onLost();
            } else if (authed && !"pong".equals(type)) {
                listener.onMessage(msg);
            }
        }
    }

    private void readProxied() throws Exception {
        listener.onReady(this);
        while (!closed) {
            String text;
            try {
                text = readFrame();
            } catch (java.net.SocketTimeoutException e) {
                continue;   // a quiet signalling channel is normal once the call is set up
            }
            if (text != null) listener.onMessage(new JSONObject(text));
        }
    }

    /** One message: its text, or null after answering a control frame. Fragmented messages are joined. */
    private String readFrame() throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        while (true) {
            int b0 = input.readUnsignedByte(), b1 = input.readUnsignedByte();
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            long len = b1 & 0x7F;
            if (len == 126) len = input.readUnsignedShort();
            else if (len == 127) len = input.readLong();
            if (len > 8 * 1024 * 1024) throw new IOException("frame too large");
            byte[] payload = new byte[(int) len];
            input.readFully(payload);   // server frames are never masked
            if (opcode == 0x8) throw new IOException("closed by server");
            if (opcode == 0x9) {
                sendFrame(0xA, payload);
                return null;
            }
            if (opcode == 0xA) return null;
            message.write(payload);
            if (fin) return message.toString("UTF-8");
        }
    }

    synchronized void sendRaw(String text) throws IOException {
        sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    /** A client frame: always masked, as RFC 6455 requires. */
    private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 14);
        f.write(0x80 | opcode);
        if (payload.length < 126) {
            f.write(0x80 | payload.length);
        } else if (payload.length < 65536) {
            f.write(0x80 | 126);
            f.write(payload.length >> 8);
            f.write(payload.length);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write((int) ((long) payload.length >> (8 * i)));
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        f.write(mask);
        for (int i = 0; i < payload.length; i++) f.write(payload[i] ^ mask[i & 3]);
        out.write(f.toByteArray());
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return sb.toString();
    }

    private static void close(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
