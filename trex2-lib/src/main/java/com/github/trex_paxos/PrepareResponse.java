package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

record PrepareResponse(Vote vote,
                       Progress progress,
                       Optional<Accept> highestUncommitted,
                       List<Accept> catchup) implements TrexMessage {
  byte from() {
    return vote.from();
  }

  long highestCommittedIndex() {
    return progress.highestCommitted();
  }

  @Override
  public void writeTo(DataOutputStream dos) throws IOException {
    vote.writeTo(dos);
    progress.writeTo(dos);
    dos.writeBoolean(highestUncommitted.isPresent());
    if (highestUncommitted.isPresent()) {
      highestUncommitted.get().writeTo(dos);
    }
    dos.writeInt(catchup.size());
    for (Accept accept : catchup) {
      accept.writeTo(dos);
    }
  }

  public static PrepareResponse readFrom(DataInputStream dis) throws IOException {
    Vote vote = Vote.readFrom(dis);
    Progress progress = Progress.readFrom(dis);
    Optional<Accept> highestUncommitted = dis.readBoolean() ? Optional.of(Accept.readFrom(dis)) : Optional.empty();
    int catchupSize = dis.readInt();
    List<Accept> catchup = new ArrayList<>();
    for (var i = 0; i < catchupSize; i++) {
      catchup.add(Accept.readFrom(dis));
    }
    return new PrepareResponse(vote, progress, highestUncommitted, catchup);
  }
}
