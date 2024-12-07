/*
 * Copyright 2024 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.logging.Logger;
import java.util.stream.Stream;

/// The TrexEngine manages the timeout behaviours that surround the core Paxos algorithm.
/// Subclasses must implement the timeout and heartbeat methods.
/// The core paxos algorithm is implemented in the TrexNode class that is wrapped by this class.
/// This method
public abstract class TrexEngine {
  static final Logger LOGGER = Logger.getLogger("");

  /// The underlying TrexNode that is the actual Part-time Parliament algorithm implementation guarded by this class.
  final protected TrexNode trexNode;

  protected boolean syncJournal = true;

  /// Create a new TrexEngine which wraps a TrexNode.
  ///
  /// @param trexNode The underlying TrexNode which must be pre-configured with a concrete Journal and QuorumStrategy.
  public TrexEngine(TrexNode trexNode) {
    this.trexNode = trexNode;
  }

  /// Create a new TrexEngine which wraps a TrexNode.
  ///
  /// @param trexNode                The underlying TrexNode which must be pre-configured with a concrete Journal and QuorumStrategy.
  /// @param hostManagedTransactions If true the TrexEngine will not sync the journal after each message. It is then the responsibility
  ///                                                   of the host application to sync the journal by calling commit on the underlying database when it has finished
  ///                                                   applying the fixed commands to the underlying database.
  @SuppressWarnings("unused")
  public TrexEngine(TrexNode trexNode, boolean hostManagedTransactions) {
    this.trexNode = trexNode;
    this.syncJournal = !hostManagedTransactions;
  }

  /// This method must schedule a call to the timeout method at some point in the future.
  /// It should first clear any existing timeout by calling clearTimeout.
  /// The timeout should be a random value between a high and low value that is specific to your message latency.
  protected abstract void setRandomTimeout();

  /// Reset the timeout for the current node to call calling the timeout method.
  /// This should cancel any existing timeout that was created by setRandomTimeout.
  protected abstract void clearTimeout();

  /// This method must schedule a call to the heartbeat method at some point in the future.
  /// The heartbeat should be a fixed period which is less than the minimum of the random timeout minimal value.
  protected abstract void setHeartbeat();

  /// Process an application command sent from a client. This is only actioned by a leader. It will return a single accept
  /// then append a fixed message as it is very cheap for another node to filter out fixed values it has already seen.
  private TrexMessage command(Command command) {
    // only if we are leader do we create the next accept message into the next log index
    final var nextAcceptMessage = trexNode.nextAcceptMessage(command);
    // we self accept
    final var r = trexNode.paxos(nextAcceptMessage);
    // the result is ignorable a self ack so does not need to be transmitted.
    assert r.commands().isEmpty() && r.messages().size() == 1 && r.messages().getFirst() instanceof AcceptResponse;
    // forward to the cluster the new accept and at the same time heartbeat a fixed message.
    return nextAcceptMessage;
  }

  public List<TrexMessage> command(List<Command> command) {
    if (trexNode.isLeader()) {
      /// toList is immutable so we concat streams first.
      return Stream.concat(
          command.stream().map(this::command),
          Stream.of(trexNode.currentFixedMessage())
      ).toList();
    } else {
      return Collections.emptyList();
    }
  }

  /// We want to be friendly to Virtual Threads so we use a semaphore with a single permit to ensure that we only
  /// process one message at a time. This is recommended over using a synchronized blocks.
  private final Semaphore mutex = new Semaphore(1);

  /// The main entry point for the Trex paxos algorithm. This method will recurse without returning when we need to
  /// send a message to ourselves. As a side effect the TrexNode journal will be updated.
  ///
  /// This method uses a mutex to allow only one thread to be processing messages at a time.
  ///
  /// @param trexMessages The batch of messages to process.
  /// @return A list of messages to send out to the cluster and/or a list of selected Commands.
  public TrexResult paxos(List<TrexMessage> trexMessages) {
    try {
      mutex.acquire();
      try {
        // we should ignore messages sent from ourselves which happens in a true broadcast network and simulation unit tests.
        final var results = trexMessages
            .stream()
            .filter(m -> m.from() != trexNode.nodeIdentifier())
            .map(this::paxosNotThreadSafe)
            .toList();
        // the progress must be synced to durable storage before any messages are sent out.
        trexNode.journal.sync();
        // merge all the results into one
        return TrexResult.merge(results);
      } finally {
        mutex.release();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("TrexEngine was interrupted awaiting the mutex probably to shutdown while under load.", e);
    }
  }

  /// This method is not public as it is not thread safe. It is called from the public paxos method which is protected
  /// by a mutex.
  ///
  /// This method will run our paxos algorithm ask to reset timeouts and heartbeats as required. Subclasses must provide
  /// the timeout and heartbeat methods.
  TrexResult paxosNotThreadSafe(TrexMessage trexMessage) {
    if (evidenceOfLeader(trexMessage)) {
      if (trexNode.getRole() != TrexNode.TrexRole.FOLLOW)
        trexNode.abdicate();
      setRandomTimeout();
    }

    final var oldRole = trexNode.getRole();

    final var result = trexNode.paxos(trexMessage);

    final var newRole = trexNode.getRole();

    if (trexNode.isLeader()) {
      setHeartbeat();
    } else if (trexNode.isRecover()) {
      setHeartbeat();
    }

    if (oldRole != newRole) {
      if (oldRole == TrexNode.TrexRole.LEAD) {
        setRandomTimeout();
      } else if (newRole == TrexNode.TrexRole.LEAD) {
        clearTimeout();
      }
    }
    return result;
  }

  boolean evidenceOfLeader(TrexMessage input) {
    return switch (input) {
      case Fixed fixed -> !trexNode.isLeader()
          && fixed.from() != trexNode.nodeIdentifier()
          && fixed.fixedLogIndex() >= trexNode.highestFixed()
      ;
      case Accept accept -> !trexNode.isLeader()
          && accept.from() != trexNode.nodeIdentifier()
          && (accept.logIndex() > trexNode.highestAccepted()
          || accept.logIndex() > trexNode.highestFixed());

      case AcceptResponse acceptResponse -> trexNode.isLeader()
          && acceptResponse.from() != trexNode.nodeIdentifier()
          && acceptResponse.highestFixedIndex() > trexNode.highestFixed();
      
      default -> false;
    };
  }

  public void start() {
    setRandomTimeout();
  }

  protected Optional<Prepare> timeout() {
    var result = trexNode.timeout();
    if (result.isPresent()) {
      LOGGER.fine(() -> "Timeout: " + trexNode.nodeIdentifier() + " " + trexNode.getRole());
      setRandomTimeout();
    }
    return result;
  }

  protected List<TrexMessage> heartbeat() {
    var result = trexNode.heartbeat();
    if (!result.isEmpty()) {
      setHeartbeat();
      LOGGER.finer(() -> "Heartbeat: " + trexNode.nodeIdentifier() + " " + trexNode.getRole() + " " + result);
    }
    return result;
  }

  protected TrexNode trexNode() {
    return trexNode;
  }
}

