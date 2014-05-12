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
        eventLoop.start();
    }

    private final Map<Integer, Request> awaitingSend = new ConcurrentHashMap<>();
    private final Map<Integer, Request> awaitingReceive = new ConcurrentHashMap<>();

    DnsClient() {
    }

    protected abstract void sendNext(Request value) throws IOException;

    protected static class Request {
        protected Request(String host, int id, CompletableFuture<InetAddress> future) {
            this.host = host;
            this.id = id;
            this.future = future;
        }

        protected final String host;
        protected final int id;
        protected final CompletableFuture<InetAddress> future;
    }

    protected static class Response {
        protected final int id;
        protected final InetAddress inetAddress;

        protected Response(int id, InetAddress inetAddress) {
            this.id = id;
            this.inetAddress = inetAddress;
        }
    }

    abstract Response receiveNext() throws IOException;

    private void sendAll() {
        Iterator<Map.Entry<Integer, Request>> iterator = awaitingSend.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Request> next = iterator.next();
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
                    request.future.complete(next.inetAddress);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public CompletableFuture<InetAddress> resolve(String host) {
        int id;
        final CompletableFuture<InetAddress> future = new CompletableFuture<>();
        do {
            id = nextId();
        } while (awaitingSend.putIfAbsent(id, new Request(host, id, future)) != null);
        return future;
    }

    private int nextId() {
        int id;
        do {
            id = ThreadLocalRandom.current().nextInt(0xFFFF);
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
