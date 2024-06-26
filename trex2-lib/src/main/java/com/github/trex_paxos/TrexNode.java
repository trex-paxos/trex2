package com.github.trex_paxos;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static com.github.trex_paxos.TrexRole.*;

public class TrexNode {
  /**
   * The current node identifier. This must be globally unique.
   */
  final byte nodeIdentifier;

  /**
   * The durable storage and durable log.
   */
  final Journal journal;

  /**
   * The quorum strategy that may be a simple majority, or FPaxos or UPaxos
   */
  final QuorumStrategy quorumStrategy;

  /**
   * If we have rebooted then we are a follower.
   */
  private TrexRole role = FOLLOW;

  /**
   * The initial progress must be loaded from the journal. TA fresh node the journal must be pre-initialised.
   */
  Progress progress;

  /**
   * During a recovery we will track all the slots that we are probing to find the highest accepted values.
   */
  NavigableMap<Long, Map<Byte, PrepareResponse>> prepareResponsesByLogIndex = new TreeMap<>();

  /**
   * When leading we will track the responses to a stream of accept messages.
   */
  NavigableMap<Long, AcceptVotes> acceptVotesByLogIndex = new TreeMap<>();

  /**
   * The host application will need to learn that work has been committed.
   */
  final Consumer<Long> endAppendToLog;

  /**
   * When we are leader we need to now the highest ballot number to use.
   */
  Optional<BallotNumber> epoch = Optional.empty();

  public TrexNode(byte nodeIdentifier, QuorumStrategy quorumStrategy, Journal journal, Consumer<Long> endAppendToLog) {
    this.nodeIdentifier = nodeIdentifier;
    this.journal = journal;
    this.quorumStrategy = quorumStrategy;
    this.endAppendToLog = endAppendToLog;
    this.progress = journal.loadProgress(nodeIdentifier);
  }
  
  private boolean invariants() {
    return switch (role) {
      case FOLLOW -> // follower is tracking no state and has no epoch
        epoch.isEmpty() && prepareResponsesByLogIndex.isEmpty() && acceptVotesByLogIndex.isEmpty();
      case RECOVER -> // candidate has an epoch and is tracking some prepare responses and/or some accept votes
        epoch.isPresent() && (!prepareResponsesByLogIndex.isEmpty() || !acceptVotesByLogIndex.isEmpty());
      case LEAD -> // leader has an epoch and is tracking no prepare responses
        epoch.isPresent() && prepareResponsesByLogIndex.isEmpty();
    };
  }

  private final List<TrexMessage> messageToSend = new ArrayList<>();

  /**
   * The main entry point for the Trex paxos algorithm.
   */
  public List<TrexMessage> paxos(TrexMessage msg) {
    // when testing we can check invariants
    assert invariants() : STR."invariants failed: \{this.role} \{this.epoch} \{this.prepareResponsesByLogIndex} \{this.acceptVotesByLogIndex}";

    messageToSend.clear();

    switch (msg) {
      case Accept accept -> {
        if (lowerAccept(progress, accept) || higherAcceptForCommittedSlot(accept, progress)) {
          messageToSend.add(nack(accept));
        } else if (equalOrHigherAccept(progress, accept)) {
          // always journal first
          journal.journalAccept(accept);
          if (higherAccept(progress, accept)) {
            // we must update promise on a higher accept http://stackoverflow.com/q/29880949/329496
            Progress updatedProgress = progress.withHighestPromised(accept.number());
            journal.saveProgress(updatedProgress);
            this.progress = updatedProgress;
          }
          messageToSend.add(ack(accept));
        } else {
          throw new AssertionError(STR."unreachable progress=\{progress}, accept=\{accept}");
        }
      }
      case Prepare prepare -> {
        var number = prepare.number();
        if (number.lessThan(progress.highestPromised()) || prepare.logIndex() <= progress.highestCommitted()) {
          // nack a low prepare else any prepare for a committed slot sending any accepts they are missing
          messageToSend.add(nack(prepare, loadCatchup(prepare.logIndex())));
        } else if (number.greaterThan(progress.highestPromised())) {
          // ack a higher prepare
          final var newProgress = progress.withHighestPromised(prepare.number());
          journal.saveProgress(newProgress);
          messageToSend.add(ack(prepare));
          this.progress = newProgress;
          // leader or recoverer should give way to a higher prepare
          if (this.role != FOLLOW)
            backdown();
        } else if (number.equals(progress.highestPromised())) {
          messageToSend.add(ack(prepare));
        } else {
          throw new AssertionError(STR."unreachable progress=\{progress}, prepare=\{prepare}");
        }
      }
      case AcceptResponse acceptResponse -> {
        if (FOLLOW != role && acceptResponse.vote().to() == nodeIdentifier) {
          // Both Leader and Recoverer can receive AcceptResponses
          final var logIndex = acceptResponse.vote().logIndex();
          Optional.ofNullable(this.acceptVotesByLogIndex.get(logIndex)).ifPresent(acceptVotes -> {
            if (!acceptVotes.chosen()) {
              acceptVotes.responses().put(acceptResponse.from(), acceptResponse);
              Set<Vote> vs = acceptVotes.responses().values().stream()
                .map(AcceptResponse::vote).collect(Collectors.toSet());
              final var quorumOutcome =
                quorumStrategy.assessAccepts(logIndex, vs);
              switch (quorumOutcome) {
                case QUORUM -> {
                  acceptVotesByLogIndex.put(logIndex, AcceptVotes.chosen(acceptVotes.accept()));
                  Optional<Long> highestCommitable = Optional.empty();
                  List<Long> deletable = new ArrayList<>();
                  List<Long> committedSlots = new ArrayList<>();
                  for (final var votesByIdMapEntry :
                    acceptVotesByLogIndex.entrySet()) {
                    if (votesByIdMapEntry.getValue().chosen()) {
                      highestCommitable = Optional.of(votesByIdMapEntry.getKey());
                      deletable.add(votesByIdMapEntry.getKey());
                      committedSlots.add(votesByIdMapEntry.getKey());
                    } else {
                      break;
                    }
                  }
                  if (highestCommitable.isPresent()) {
                    // run the callback
                    for (var slot : committedSlots) {
                      endAppendToLog.accept(slot);
                    }
                    // free the memory
                    for (final var deletableId : deletable) {
                      acceptVotesByLogIndex.remove(deletableId);
                    }
                    // we have committed
                    this.progress = progress.withHighestCommitted(highestCommitable.get());
                    // let the cluster know
                    messageToSend.add(new Commit(highestCommitable.get()));
                  }
                  // if we still have missing votes resend the accepts
                  for (var entry : acceptVotesByLogIndex.entrySet().stream().filter(e -> !e.getValue().chosen()).toList()) {
                    // TODO could be point to point rather than broadcast
                    messageToSend.add(entry.getValue().accept());
                  }
                }
                case NO_DECISION -> {
                  // do nothing as a quorum has not yet been reached.
                }
                case NO_QUORUM ->
                  // we are unable to achieve a quorum, so we must back down as to another leader
                  backdown();
              }
            }
          });
        }
      }
      case PrepareResponse prepareResponse -> {
        if (RECOVER == role) {
          final var catchup = prepareResponse.catchup();
          final long highestCommittedOther = prepareResponse.highestCommittedIndex();
          final long highestCommitted = progress.highestCommitted();
          if (highestCommitted < highestCommittedOther) {
            // we are behind so now try to catch up
            saveCatchup(highestCommittedOther, catchup);
            // this may be evidence of a new leader so back down
            backdown();
          } else {
            if (prepareResponse.vote().to() == nodeIdentifier) {
              final byte from = prepareResponse.from();
              final long logIndex = prepareResponse.vote().logIndex();
              Optional.ofNullable(prepareResponsesByLogIndex.get(logIndex)).ifPresent(
                votes -> {
                  votes.put(from, prepareResponse);
                  Set<Vote> vs = votes.values().stream()
                    .map(PrepareResponse::vote).collect(Collectors.toSet());
                  final var quorumOutcome = quorumStrategy.assessPromises(logIndex, vs);
                  switch (quorumOutcome) {
                    case NO_DECISION ->
                      // do nothing as a quorum has not yet been reached.
                      prepareResponsesByLogIndex.put(logIndex, votes);
                    case NO_QUORUM ->
                      // we are unable to achieve a quorum, so we must back down
                      backdown();
                    case QUORUM -> {
                      // first issue new prepare messages for higher slots
                      votes.values().stream()
                        .map(PrepareResponse::progress)
                        .map(Progress::highestAccepted)
                        .max(Long::compareTo)
                        .ifPresent(higherAcceptedSlot -> {
                          final long highestLogIndexProbed = prepareResponsesByLogIndex.lastKey();
                          if (higherAcceptedSlot > highestLogIndexProbed) {
                            epoch.ifPresent(epoch ->
                              LongStream.range(higherAcceptedSlot + 1, highestLogIndexProbed + 1)
                                .forEach(slot -> {
                                  prepareResponsesByLogIndex.put(slot, new HashMap<>());
                                  messageToSend.add(new Prepare(slot, epoch));
                                }));
                          }
                        });

                      // find the highest accepted command if any
                      AbstractCommand highestAcceptedCommand = votes.values().stream()
                        .map(PrepareResponse::highestUncommitted)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .max(Accept::compareTo)
                        .map(Accept::command)
                        .orElse(NoOperation.NOOP);

                      epoch.ifPresent(e -> {
                        // use the highest accepted command to issue an Accept
                        Accept accept = new Accept(logIndex, e, highestAcceptedCommand);
                        // issue the accept messages
                        messageToSend.add(accept);
                        // create the empty map to track the responses
                        acceptVotesByLogIndex.put(logIndex, new AcceptVotes(accept));
                        // self vote for the Accept
                        selfVoteOnAccept(accept);
                        // we are no long awaiting the prepare for the current slot
                        prepareResponsesByLogIndex.remove(logIndex);
                        // if we have had no evidence of higher accepted values we can promote
                        if (prepareResponsesByLogIndex.isEmpty()) {
                          this.role = LEAD;
                        }
                      });
                    }
                  }
                }
              );
            }
          }
        }
      }
      case Commit commit -> journal.committed(nodeIdentifier, commit.logIndex());
      case Catchup(final var _, final var highestCommittedOther) ->
        messageToSend.add(new CatchupResponse(progress.highestCommitted(), loadCatchup(highestCommittedOther)));
      case CatchupResponse(final var highestCommittedOther, final var catchup) ->
        saveCatchup(highestCommittedOther, catchup);
    }
    return null;
  }

  private void selfVoteOnAccept(Accept accept) {
    if (lowerAccept(progress, accept) || higherAcceptForCommittedSlot(accept, progress)) {
      acceptVotesByLogIndex.get(accept.logIndex()).responses().put(nodeIdentifier, nack(accept));
    } else if (equalOrHigherAccept(progress, accept)) {
      // always journal first
      journal.journalAccept(accept);
      if (higherAccept(progress, accept)) {
        // we must update promise on a higher accept http://stackoverflow.com/q/29880949/329496
        Progress updatedProgress = progress.withHighestPromised(accept.number());
        journal.saveProgress(updatedProgress);
        this.progress = updatedProgress;
      }
    }
  }

  private void saveCatchup(long highestCommittedOther, List<Accept> catchup) {
    for (Accept accept : catchup) {
      final var slot = accept.logIndex();
      if (slot <= highestCommittedOther) {
        continue;
      }
      if (equalOrHigherAccept(progress, accept)) {
        // always journal first
        journal.journalAccept(accept);

        if (higherAccept(progress, accept)) {
          // we must update promise on a higher accept http://stackoverflow.com/q/29880949/329496
          Progress updatedProgress = progress.withHighestPromised(accept.number());
          journal.saveProgress(updatedProgress);
          this.progress = updatedProgress;
        }
      }
    }
  }

  private List<Accept> loadCatchup(long highestCommittedOther) {
    final long highestCommitted = progress.highestCommitted();
    List<Accept> catchup = new ArrayList<>();
    for (long slot = highestCommitted + 1; slot < highestCommittedOther; slot++) {
      journal.loadAccept(slot).ifPresent(catchup::add);
    }
    return catchup;
  }

  void backdown() {
    this.role = FOLLOW;
    prepareResponsesByLogIndex = new TreeMap<>();
    acceptVotesByLogIndex = new TreeMap<>();
    epoch = Optional.empty();
  }

  /**
   * Send a positive vote message to the leader.
   *
   * @param accept The accept message to acknowledge.
   */
  AcceptResponse ack(Accept accept) {
    return
      new AcceptResponse(
        new Vote(nodeIdentifier, accept.number().nodeIdentifier(), accept.logIndex(), true)
        , progress);
  }

  /**
   * Send a negative vote message to the leader.
   *
   * @param accept The accept message to reject.
   */
  AcceptResponse nack(Accept accept) {
    return new AcceptResponse(
      new Vote(nodeIdentifier, accept.number().nodeIdentifier(), accept.logIndex(), false)
      , progress);
  }

  /**
   * Send a positive prepare response message to the leader.
   *
   * @param prepare The prepare message to acknowledge.
   */
  PrepareResponse ack(Prepare prepare) {
    return new PrepareResponse(
      new Vote(nodeIdentifier, prepare.number().nodeIdentifier(), prepare.logIndex(), true),
      progress,
      journal.loadAccept(prepare.logIndex()),
      List.of());
  }

  /**
   * Send a negative prepare response message to the leader.
   *
   * @param prepare The prepare message to reject.
   * @param catchup The list of accept messages to send to the leader.
   */
  PrepareResponse nack(Prepare prepare, List<Accept> catchup) {
    return new PrepareResponse(
      new Vote(nodeIdentifier, prepare.number().nodeIdentifier(), prepare.logIndex(), false),
      progress,
      journal.loadAccept(prepare.logIndex()),
      catchup);
  }

  /**
   * Client request to append a command to the log.
   *
   * @param command The command to append.
   * @return The possible log index of the appended command.
   */
  public Optional<Long> startAppendToLog(Command command) {
    if (epoch.isPresent()) {
      final long slot = progress.highestAccepted() + 1;
      final var accept = new Accept(slot, epoch.get(), command);
      // FIXME what now?
      return Optional.of(slot);
    } else return Optional.empty();
  }

  static boolean equalOrHigherAccept(Progress progress, Accept accept) {
    return progress.highestPromised().lessThanOrEqualTo(accept.number());
  }

  static boolean higherAcceptForCommittedSlot(Accept accept, Progress progress) {
    return accept.number().greaterThan(progress.highestPromised()) &&
      accept.logIndex() <= progress.highestCommitted();
  }

  static Boolean lowerAccept(Progress progress, Accept accept) {
    return accept.number().lessThan(progress.highestPromised());
  }

  static Boolean higherAccept(Progress progress, Accept accept) {
    return accept.number().greaterThan(progress.highestPromised());
  }
}
