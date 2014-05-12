package com.thomas.dns.client;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyBoundException;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

class UdpRacingDnsClient extends DnsClient implements AutoCloseable {
    private final List<InetSocketAddress> resolvers;
    private final DatagramChannel channel;
    private final ByteBuffer buff = ByteBuffer.allocate(0xFFFF);

    public UdpRacingDnsClient(List<byte[]> resolvers) throws IOException {
        if (resolvers == null || resolvers.isEmpty()) {
            throw new IllegalArgumentException("null or empty resolvers ip not allowed");
        }
        this.resolvers = new ArrayList<>(resolvers.size());
        for (byte[] ip : resolvers) {
            this.resolvers.add(new InetSocketAddress(InetAddress.getByAddress(ip), 53));
        }
        channel = DatagramChannel.open(StandardProtocolFamily.INET);
        channel.configureBlocking(false);
        for (; ; ) {
            try {
                int port = ThreadLocalRandom.current().nextInt(0x0FFF, 0xFFFF);
                channel.bind(new InetSocketAddress((InetAddress) null, port));
                break;
            } catch (AlreadyBoundException | BindException ignored) {
            }
        }
    }

    @Override
    protected void sendNext(Request value) throws IOException {
        createDnsQuery(value);
        for (InetSocketAddress addr : resolvers) {
            channel.send(buff, addr);
            buff.flip();
        }
    }

    private void createDnsQuery(Request value) {
        buff.rewind();
        //create
    }

    @Override
    Response receiveNext() throws IOException {
        if (channel.receive(buff) != null) {
            return readDnsResponse();
        }
        return null;
    }

    private Response readDnsResponse() {
        //read
        buff.reset();
        return null;
    }

    @Override
    public void close() {
        super.close();
        try {
            channel.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
