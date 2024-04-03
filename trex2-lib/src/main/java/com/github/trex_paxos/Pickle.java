package com.github.trex_paxos;

import java.io.*;

public class Pickle {
    public static TrexMessage read(byte[] bytes) {
        ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
        DataInputStream dis = new DataInputStream(bis);
        try {
            MessageType messageType = MessageType.fromId(dis.readByte());
            switch (messageType) {
                case MessageType.Prepare:
                    return Prepare.readFrom(dis);
                case MessageType.PrepareAck:
                    return PrepareAck.readFrom(dis);
                case MessageType.PrepareNack:
                    return PrepareNack.readFrom(dis);
                case MessageType.Accept:
                    return Accept.readFrom(dis);
                case MessageType.AcceptAck:
                    return AcceptAck.readFrom(dis);
                case MessageType.AcceptNack:
                    return AcceptNack.readFrom(dis);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        throw new AssertionError("unreachable as the switch statement is exhaustive");
    }

    public static byte[] write(TrexMessage message) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(byteArrayOutputStream);
        dos.writeByte(MessageType.fromPaxosMessage(message).id());
        message.writeTo(dos);
        dos.close();
        return byteArrayOutputStream.toByteArray();
    }
}
