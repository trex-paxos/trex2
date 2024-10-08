package com.github.trex_paxos.msg;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record CatchupResponse(byte from, byte to, long highestCommittedIndex,
                              List<Accept> catchup) implements TrexMessage, DirectMessage {
  public static CatchupResponse readFrom(DataInputStream dis) throws IOException {
    final byte from = dis.readByte();
    final byte to = dis.readByte();
    long highestCommittedIndex = dis.readLong();
    int catchupSize = dis.readInt();
    List<Accept> catchup = new ArrayList<>();
    for (var i = 0; i < catchupSize; i++) {
      catchup.add(Accept.readFrom(dis));
    }
    return new CatchupResponse(from, to, highestCommittedIndex, catchup);
  }

  public void writeTo(DataOutputStream dos) throws IOException {
    dos.writeByte(from);
    dos.writeByte(to);
    dos.writeLong(highestCommittedIndex);
    dos.writeInt(catchup.size());
    for (Accept accept : catchup) {
      accept.writeTo(dos);
    }
  }
}
