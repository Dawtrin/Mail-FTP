package vn.edu.vku.mailserver.integration;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Proxy UDP giả lập mất gói: bỏ N request tới server hoặc N phản hồi về client. */
final class LossyProxy implements AutoCloseable {
    private final DatagramSocket front;
    private final DatagramSocket back;
    private final InetSocketAddress server;
    private volatile SocketAddress client;
    private final AtomicInteger dropRequests = new AtomicInteger();
    private final AtomicInteger dropReplies = new AtomicInteger();

    LossyProxy(int serverPort) throws IOException {
        InetAddress lo = InetAddress.getLoopbackAddress();
        front = new DatagramSocket(0, lo);
        back = new DatagramSocket(0, lo);
        server = new InetSocketAddress(lo, serverPort);
        daemon(() -> pump(front, true));
        daemon(() -> pump(back, false));
    }

    int port() { return front.getLocalPort(); }
    void dropNextRequests(int n) { dropRequests.set(n); }
    void dropNextReplies(int n)  { dropReplies.set(n); }

    private void pump(DatagramSocket from, boolean towardServer) {
        byte[] buf = new byte[2048];
        DatagramPacket in = new DatagramPacket(buf, buf.length);
        while (!from.isClosed()) {
            try {
                in.setLength(buf.length);
                from.receive(in);
                byte[] data = Arrays.copyOf(buf, in.getLength());
                if (towardServer) {
                    client = in.getSocketAddress();
                    if (take(dropRequests)) continue;
                    back.send(new DatagramPacket(data, data.length, server));
                } else {
                    if (take(dropReplies)) continue;
                    front.send(new DatagramPacket(data, data.length, client));
                }
            } catch (IOException e) {
                return;
            }
        }
    }

    private static boolean take(AtomicInteger counter) {
        return counter.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }

    private static void daemon(Runnable r) {
        Thread t = new Thread(r, "lossy-proxy");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void close() {
        front.close();
        back.close();
    }
}
