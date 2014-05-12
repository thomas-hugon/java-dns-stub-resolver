package com.thomas.dns.client;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public abstract class DnsClient implements AutoCloseable {

    private final static ConcurrentHashMap.KeySetView<DnsClient, Boolean> instances = ConcurrentHashMap.newKeySet();
    private static final Thread eventLoop = new Thread(() -> {
        while (!Thread.currentThread().isInterrupted()) {
            synchronized (instances) {
                while (!Thread.currentThread().isInterrupted() && instances.isEmpty()) {
                    try {
                        instances.wait(10);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                instances.forEach(DnsClient::sendAll);
                instances.forEach(DnsClient::receiveAll);
            }
            Thread.yield();
        }
    });

    static {
        eventLoop.setDaemon(true);
        eventLoop.start();
    }

    private final Map<Short, Request> awaitingSend = new ConcurrentHashMap<>();
    private final Map<Short, Request> awaitingReceive = new ConcurrentHashMap<>();

    DnsClient() {
    }

    protected abstract void sendNext(Request value) throws IOException;

    protected static class Request {
        protected Request(String host, short id, CompletableFuture<InetAddress> future) {
            this.host = host;
            this.id = id;
            this.future = future;
        }

        protected final String host;
        protected final short id;
        protected final CompletableFuture<InetAddress> future;
    }

    protected static class Response {
        protected final short id;
        protected final byte[] inetAddress;

        protected Response(short id, byte[] inetAddress) {
            this.id = id;
            this.inetAddress = inetAddress;
        }
    }

    abstract Response receiveNext() throws IOException;

    private void sendAll() {
        Iterator<Map.Entry<Short, Request>> iterator = awaitingSend.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Short, Request> next = iterator.next();
            awaitingReceive.put(next.getKey(), next.getValue());
            iterator.remove();
            try {
                sendNext(next.getValue());
            } catch (Exception e) {
                awaitingReceive.remove(next.getKey());
                next.getValue().future.completeExceptionally(e);
            }
        }
    }

    private void receiveAll() {
        Response next;
        try {
            while ((next = receiveNext()) != null) {
                Request request = awaitingReceive.remove(next.id);
                if (request != null) {
                    try {
                        InetAddress byAddress = next.inetAddress == null ? null : InetAddress.getByAddress(next.inetAddress);
                        request.future.complete(byAddress);
                    } catch (Exception e) {
                        request.future.completeExceptionally(e);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public CompletableFuture<InetAddress> resolve(String host) {
        short id;
        final CompletableFuture<InetAddress> future = new CompletableFuture<>();
        do {
            id = nextId();
        } while (awaitingSend.putIfAbsent(id, new Request(host, id, future)) != null);
        return future;
    }

    private short nextId() {
        short id;
        do {
            id = (short) ThreadLocalRandom.current().nextInt(Short.MIN_VALUE, Short.MAX_VALUE + 1);
        } while (awaitingSend.containsKey(id) || awaitingReceive.containsKey(id));
        return id;
    }

    @Override
    public void close() {
        synchronized (instances) {
            instances.remove(this);
        }
    }

    public static DnsClient udpRacingDnsClient(byte[]... resolvers) throws IOException {
        UdpRacingDnsClient udpRacingDnsClient = new UdpRacingDnsClient(Arrays.asList(resolvers));
        synchronized (instances) {
            instances.add(udpRacingDnsClient);
            instances.notifyAll();
        }
        return udpRacingDnsClient;
    }
}
