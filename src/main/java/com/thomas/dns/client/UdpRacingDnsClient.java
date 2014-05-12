package com.thomas.dns.client;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyBoundException;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

class UdpRacingDnsClient extends DnsClient implements AutoCloseable {
    private final List<InetSocketAddress> resolvers;
    private final DatagramChannel channel;
    private final ByteBuffer buff = ByteBuffer.allocateDirect(0xFFFF);

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
        buff.clear();
        createDnsQuery(value);
        for (InetSocketAddress addr : resolvers) {
            buff.flip();
            channel.send(buff, addr);
        }
    }

    private void createDnsQuery(Request req) {
        //java endianness = dns endianness

        // 1.header
        buff.putShort(req.id);
        //      flags: QR(1)=0,OPCODE(4)=0,AA(1)=0,TC(1)=0,RD(1)=1,RA(1)=0,Z(3)=0,RDCODE(4)=0
        buff.putShort((short) 0x0100);
        //      QDCOUNT(16)=1,ANCOUNT(16)=0,NSCOUNT(16)=0,ARCOUNT(16)=0
        buff.putShort((short) 0x0001);
        buff.putShort((short) 0x0000);
        buff.putShort((short) 0x0000);
        buff.putShort((short) 0x0000);

        // 2.Question
        //      QNAME
        for (String domain : req.host.split("\\.")) {
            byte[] bytes = domain.getBytes(StandardCharsets.US_ASCII);
            buff.put((byte) bytes.length);
            buff.put(bytes);
        }
        buff.put((byte) 0);
        //      QTYPE(16)=1->A (IP4) | 28->AAAA (IP6)
        buff.putShort((short) 0x0001);
        //      QCLASS(16)=1 //IN
        buff.putShort((short) 0x0001);
    }

    @Override
    Response receiveNext() throws IOException {
        buff.clear();
        if (channel.receive(buff) != null) {
            buff.flip();
            return readDnsResponse();
        }
        return null;
    }

    private Response readDnsResponse() {
        //java endianness = dns endianness

        // 1.header
        short id = buff.getShort();
        //      flags: QR(1)=1,OPCODE(4)=0,AA(1)=0,TC(1)=0,RD(1)=1,RA(1)=0,Z(3)=0,RDCODE(4)=0
        short flags = buff.getShort();
        byte qr = (byte) ((flags & 0b1000000000000000) >>> 15);
        byte opcode = (byte) ((flags & 0b0111100000000000) >>> 11);
        byte aa = (byte) ((flags & 0b0000010000000000) >>> 10);
        byte tc = (byte) ((flags & 0b0000001000000000) >>> 9);
        byte rd = (byte) ((flags & 0b0000000100000000) >>> 8);
        byte ra = (byte) ((flags & 0b0000000010000000) >>> 7);
        byte z = (byte) ((flags & 0b0000000001110000) >>> 4);
        byte rcode = (byte) (flags & 0b0000000000001111);
        if (qr != 1) {
            //fail future because it's not a response ?
            return new Response(id, null);
        }
        //      QDCOUNT(16)=1,ANCOUNT(16)=0,NSCOUNT(16)=0,ARCOUNT(16)=0
        short qdcount = buff.getShort();
        short ancount = buff.getShort();
        short nscount = buff.getShort();
        short arcount = buff.getShort();
        if (ancount == 0 || qdcount != 1) {
            //fail future because there isn't the same number of questions or there is no answer
            return new Response(id, null);
        }

        // 2.Questions
        //      QNAME
        byte zerosize = 0;
        while ((zerosize = buff.get()) != 0) ;
        //should read host to ensure it is the same as requested
        //      QTYPE(16)=1->A (IP4) | 28->AAAA (IP6)
        short qtype = buff.getShort();
        //      QCLASS(16)=1 //IN
        short qclass = buff.getShort();

        //3. Answers
        //take only the first answer
        //      name. should be the same as the only one in question... doesn't matter here
        byte lbl = buff.get();
        if ((lbl & 0b11000000) == 0b11000000) {
            buff.get();
        } else {
            while ((zerosize = buff.get()) != 0) ;
        }
        //      TYPE(16)=1->A (IP4) | 28->AAAA (IP6)
        short type = buff.getShort();
        //      CLASS(16)=1 //IN
        short clazz = buff.getShort();
        if (type != 1 || clazz != 1) {
            System.err.println("type: " + type + ", class: " + clazz);
            return new Response(id, null);
        }
        long ttl = Integer.toUnsignedLong(buff.getInt());
        short rdlength = buff.getShort();
        //should take into account ipv6 in a possible future.....
        if (rdlength != 4) {
            return new Response(id, null);
        }
        byte[] addr = new byte[4];
        buff.get(addr);
        return new Response(id, addr);
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
