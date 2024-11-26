## Trex2: Paxos Algorithm Strong Consistency for state replication on the Java JVM

This is a work in progress as more tests are to be written. At this point it is not recommended for production use.

### Introduction

This library implements Lamport's Paxos protocol for cluster replication, as described in Lamport's 2001 paper [Paxos Made Simple](https://lamport.azurewebsites.net/pubs/paxos-simple.pdf). While distributed systems are inherently complex, the core Paxos algorithm is mechanically straightforward when adequately understood. This implementation achieves consistency with the mathematical minimum of message exchanges without requiring external leader election services. 

### Core Concepts

A common misconception is failing to recognize that Paxos is inherently Multi-Paxos. As Lamport states in "Paxos Made Simple" (p. 10):

> A newly chosen leader executes phase 1 for infinitely many instances of the consensus algorithm. Using the same proposal number for all instances, it can do this by sending a single reasonably short message to the other servers.

This enables efficient streaming of only `accept` messages until a leader crashes or becomes network isolated. 

To replicate the state of any server we simple need to apply the same command at each server in the same order. The paper states (p. 8):

> A simple way to implement a distributed system is as a collection of clients that issue commands to a central server. The server can be described as a deterministic state machine that performs client commands in some sequence. The state machine has a current state; it performs a step by taking as input a command and producing an output and a new state.

For example, in a key-value store, commands might be `put(k,v)`, `get(k)` or `remove(k)` operations. These commands form the "values" that must be applied consistently at each node in the cluster. 

The challenge is to make the stream of commands that are chosen at each server consistent when messages are lost and servers crash. 

Checkout the wiki post [Cluster Replication With Paxos for the Java Virtual Machine](https://github.com/trex-paxos/trex-paxos-jvm/wiki) for the full description of this implementation of [Paxos Made Simple](https://lamport.azurewebsites.net/pubs/paxos-simple.pdf).

### The Paxos Protocol 

We must fix the same commands into the same command log stream index, known as a log slot, at each server: 

* Commands (aka values) are replicated as byte arrays (supporting JSON, protobuf, Avro)
* Each command is assigned to a sequential log index (slot)
* Leaders propose commands using `accept(S,N,V)` where:
  * S: logical log index/slot
  * N: proposal number unique to a leader
  * V: proposed command/value
* Upon a majority positive response to any `accept` message the value held in that the slot is fixed by the algorithm and will not change. 

Whenever a node receives a message with a higher `N` that it replies to positively, it has made a promise to reject any further messages that have a lower `N`. 

As Lamport specifies the proposal number on (p. 8):

> Different proposers choose their numbers from disjoint sets of numbers, so two different proposers never issue a proposal with the same number."

This is achieved by encoding the node identifier in each `N`s lower bits.

Lamport explicitly defines leadership (p. 6):

> The algorithm chooses a leader, which plays the roles of the distinguished proposer and the distinguished learner.

On leader election (p. 7):

> A reliable algorithm for electing a proposer must use either randomness or realtime — for example, by using timeouts. However, safety is ensured regardless of the success or failure of the election.

The novelty of Paxos was that it did not require real-time clocks. This implementation uses random timeouts: 

1. Leader sends `prepare(N,S)` for slots not known to be fixed
2. Nodes respond with promise messages containing any uncommitted `{N,V}` pairs at that slot `S`
3. Leader selects the `V` that was with the highest `N` value from a majority of responses
4. Leader sends fresh `accept(S,N',V)` messages with selected commands under new `N'`
5. Commit messages (optimization) can be piggybacked on subsequent accept messages

Again, whenever a node receives a message with a higher `N` that it replies to positively, it has made a promise to reject any further messages that have a lower `N`. 

Whatever value at a given slot is held by a majority of nodes it can not not change value. The leader listens to the response messages of followers and learns the values is It can then send a short `commit(S,N)` message to let the other nodes know. he concept of a commit message is not covered in the original papers but is a standard optimisation known as a Paxos "phase 3" message. Yet, we do not need to send it in a separate network packet. It can piggyback at the front of the network packet that holds the next `accept` message. 

When each node learns that slot `s` is fixed records this as the maximum committed index. It will then up-call the command value `V` to the host application. This will be an application-specific callback that can do whatever the host application desires. The point is that every node will up-call the same command values in the same order. Nodes will ignore any messages that are less than or equal to their committed index. 

This library uses messages similar to the following code to allow nodes to learn about which commands are fixed in slots:

```java
public record Prepare( long logIndex,
                       BallotNumber number ) {}

public record PrepareResponse(
    long logIndex,
    BallotNumber number,
    boolean vote,
    Optional<Accept> highestUncommitted ) {}

public record Accept( long logIndex,
                      BallotNumber number,
                      Command command ) {}

public record AcceptResponse(
    long logIndex,
    BallotNumber number,
    boolean vote ){}

public record BallotNumber(int counter, byte nodeIdentifier) {}

public record Command( String id,
                       byte[] operationBytes){}
```

The state of each node is similar to the following model: 

```java
public record Progress( BallotNumber highestPromised,
                        long committedIndex
) {}

public interface Journal {
   void saveProgress(Progress progress);
   void write(long logIndex, Command command);
   Command read(long logIndex);
   void sync();
}
```

The progress of each node is it's highest promised `N` and it's highest committed slot `S`. The command values are journaled to a given slot index. Journal writes must be crash-proof (disk flush or equivalent). The `sycn()` method of the journal must first flush any commands into their slots and only then flush the `progress`. 

The final question is what happens when nodes have missed messages. They can request retransmission using a `catchup` message. The additional messages of the protocol are messages to learn which commands have been fixed: 

```java
public record Commit(
    BallotNumber number,
    long committedLogIndex ) {}

public record Catchup(long highestCommitedIndex ) {}

public record CatchupResponse( List<Command> catchup ) {}
```

It is important to note that additional properties are in the real code. These are used to pass information between nodes. For example; a node trying to lead may not know the full range of slots that a previous leader has possibly fixed a value. One node in any majority does know. So in the `PrepareReponse` messages we add a `higestAcceptedIndex` property. A node that is attempting to load will then learn the maximum range of slows that it must probe with `prepare` messages. 

See the wiki for more detail. 

### Project Goals

- Implement state replication with The Part-Time Parliament Protocol (aka Multi Paxos) as documented by Leslie Lamport
  in [Paxos Made Simple](https://lamport.azurewebsites.net/pubs/paxos-simple.pdf).
- Implement the protocol in a way that is easy to understand and verify.
- Write a test harness that can inject rolling network partitions.
- Write property based tests to exhaustively verify correctness.
- Ship with zero third-party libraries outside the java base packages.
- Run on Java 22+ for Data Oriented Programming.
- Virtual thread friendly on Java 22+.
- Embeddable in other applications.
- Be paranoid about correctness. This implementation will throw an Error when it can no longer guarantee correctness.
  incorrect result.

### Non-Goals

 - Demonstrate arbitrary Paxos use cases. 
 - Backport to Java 11 LTS. 

## Development Setup

After cloning the repository, run:

```bash
./setup-hooks.sh
```

# Releases

TBD

## Tentative Roadmap

The list of tasks: 

- [x] Implement the Paxos Parliament Protocol for log replication.
- [x] Write a test harness that injects rolling network partitions.
- [ ] Write property based tests to exhaustively verify correctness.
- [ ] Implement a trivial replicated k-v store.
- [ ] Implement cluster membership changes as UPaxos.
- [ ] Add in optionality so that randomised timeouts can be replaced by some other leader failover detection and voting
  mechanism (e.g. JGroups).

## Attribution

The TRex icon is Tyrannosaurus Rex by Raf Verbraeken from the Noun Project licensed under [CC3.0](http://creativecommons.org/licenses/by/3.0/us/)
