package com.github.trex_paxos.msg;

import java.io.*;

/**
 * Pickle is a utility class for serializing and deserializing `TrexMessage`s or the `Progress` as binary data.
 * For the algorithm to work correctly only the `Progress` and `Accept` messages need to be durable on disk.
 * This means that Trex itself only uses the Pickle class to serialize and deserialize the `JournalRecord` interface.
 * You may choose to use the Pickle class to serialize and deserialize other messages as well. Alternatively your
 * application can use a different serialization mechanism as your wire format such as JSON.
 */
public class Pickle {
  // TODO consider moving all of the DataInputStream and DataOutputStream code into the Pickle class. This is because
  // teams might want to use there own serialization mechanism for the wire format.
  public static TrexMessage readMessage(byte[] bytes) {
    ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
    DataInputStream dis = new DataInputStream(bis);
    try {
      MessageType messageType = MessageType.fromMessageId(dis.readByte());
      return switch (messageType) {
        case MessageType.Prepare -> Prepare.readFrom(dis);
        case MessageType.PrepareResponse -> PrepareResponse.readFrom(dis);
        case MessageType.Accept -> Accept.readFrom(dis);
        case MessageType.AcceptResponse -> AcceptResponse.readFrom(dis);
        case MessageType.Commit -> Commit.readFrom(dis);
        case MessageType.Catchup -> Catchup.readFrom(dis);
      };
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static byte[] writeMessage(TrexMessage message) throws IOException {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(byteArrayOutputStream);
    dos.writeByte(MessageType.fromPaxosMessage(message).id());
    message.writeTo(dos);
    dos.close();
    return byteArrayOutputStream.toByteArray();
  }

  public static byte[] writeProgress(Progress progress) throws IOException {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(byteArrayOutputStream);
    progress.writeTo(dos);
    dos.close();
    return byteArrayOutputStream.toByteArray();
  }

  public static Progress readProgress(byte[] pickled) throws IOException {
    ByteArrayInputStream bis = new ByteArrayInputStream(pickled);
    DataInputStream dis = new DataInputStream(bis);
    return Progress.readFrom(dis);
  }

  public static long uncheckedReadLong(DataInputStream dis) {
    try {
      return dis.readLong();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void uncheckedWriteLong(DataOutputStream dos, long i) {
    try {
      dos.writeLong(i);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
