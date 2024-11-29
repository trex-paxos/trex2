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
package com.github.trex_paxos.msg;

import com.github.trex_paxos.Vote;

import java.util.Optional;

/// A PrepareResponse is a response to a {@link Prepare} message. It contains the vote and the highest unfixed log entry if any.
/// When the vote is positive then we have made a promise to not accept any future Prepare or Accept messages with a lower ballot number.
///
/// @param from see {@link TrexMessage}
/// @param to   see {@link DirectMessage}
/// @param vote whether wre have voted for or voted against the Prepare message based on our past promises. .
/// @param journaledAccept the highest unfixed log entry if any.
/// @param highestAcceptedIndex additional information about the highest accepted index so that a leader can learn of more slots that it needs to recover.
public record PrepareResponse(
    byte from,
    byte to,
    Vote vote,
    Optional<Accept> journaledAccept,
    long highestAcceptedIndex
) implements TrexMessage, DirectMessage {
// TODO should we send the highest promise in case it is a nack?
}
