/*
 *     Copyright (C) 2026  RetroLink contributors
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#ifndef LIBRETRODROID_NETPLAY_H
#define LIBRETRODROID_NETPLAY_H

#include <array>
#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <functional>
#include <map>
#include <mutex>
#include <vector>

namespace libretrodroid {

// Online multiplayer for games that are played on one console ("play together").
//
// Every player runs the same game. Each frame, every player sends their button state for
// a frame a few steps ahead (the input delay). A frame only runs once the inputs of all
// players for that frame have arrived, so all copies of the game stay identical.
// The host (client 0) sends its game state when someone joins, and again whenever the
// players' memory checksums stop matching.
//
// Player with client id N controls controller port N. Locally, the device's own
// controller is always read from port 0.
class Netplay {
public:
    static Netplay& getInstance() {
        static Netplay instance;
        return instance;
    }
    Netplay(Netplay const&) = delete;
    void operator=(Netplay const&) = delete;

    static constexpr int MAX_PLAYERS = 4;

    enum Status : int {
        STATUS_INACTIVE = 0,
        STATUS_WAITING_FOR_HOST = 1,  // Joined, waiting for the host's game state.
        STATUS_RUNNING = 2,
        STATUS_WAITING_FOR_PLAYERS = 3,  // Stalled because another player's input is late.
    };

    // What the sync engine needs from the emulator, all called on the emulation thread.
    struct Hooks {
        std::function<std::vector<uint8_t>()> serialize;
        std::function<bool(const uint8_t*, size_t)> unserialize;
        std::function<uint32_t()> memoryChecksum;
        std::function<int16_t(unsigned device, unsigned index, unsigned id)> localInput;
    };

    struct Outgoing {
        uint16_t target;
        std::vector<uint8_t> data;
    };

    // Thread-safe, called from Java.
    void requestStart(uint16_t localClientId, int inputDelayFrames);
    void requestStop();
    void pushIncoming(uint16_t fromClientId, const uint8_t* data, size_t size);
    void pushPeerJoined(uint16_t clientId);
    void pushPeerLeft(uint16_t clientId);
    bool takeOutgoing(Outgoing& result, int timeoutMs);
    void wakeTakers();
    int status() const { return currentStatus.load(); }

    // Emulation thread. beginFrame returns false when this frame must not run yet.
    bool beginFrame(const Hooks& hooks);
    void endFrame(const Hooks& hooks);
    bool overridesInput() const { return active && !waitingForState; }
    int16_t inputState(unsigned port, unsigned device, unsigned index, unsigned id) const;
    void reset();

    // Message types, first byte of every packet.
    enum MessageType : uint8_t {
        MSG_INPUT = 1,
        MSG_STATE_CHUNK = 2,
        MSG_CHECKSUM = 3,
        MSG_REQUEST_STATE = 4,
    };

    static constexpr uint16_t BROADCAST = 0xFFFF;
    static constexpr size_t STATE_CHUNK_SIZE = 60 * 1024;
    static constexpr uint32_t CHECKSUM_INTERVAL = 120;
    static constexpr int STALL_FRAMES_BEFORE_WAITING = 20;

    // Public so tests can run several players in one process. The app uses getInstance().
    Netplay() = default;

private:
    struct PortInput {
        uint16_t buttons = 0;
        int16_t analog[4] = {0, 0, 0, 0};  // left x, left y, right x, right y
    };

    struct FrameInputs {
        std::array<PortInput, MAX_PLAYERS> ports {};
        uint8_t received = 0;  // bit per port
    };

    enum EventType : uint8_t { EVENT_START, EVENT_STOP, EVENT_DATA, EVENT_JOINED, EVENT_LEFT };

    struct Event {
        EventType type;
        uint16_t clientId;
        int value;
        std::vector<uint8_t> data;
    };

    void pushEvent(Event event);
    void send(uint16_t target, std::vector<uint8_t> data);
    void processEvents(const Hooks& hooks);
    void handleData(uint16_t from, const std::vector<uint8_t>& data, const Hooks& hooks);
    void handleStateChunk(const std::vector<uint8_t>& data, const Hooks& hooks);
    void resync(const Hooks& hooks);
    void restartAt(uint32_t frame);
    PortInput captureLocalInput(const Hooks& hooks) const;
    void sendLocalInput(uint32_t frame, const PortInput& input);
    void compareChecksum(uint32_t frame, uint32_t checksum, const Hooks& hooks);
    void updateStatus(Status status);

    // Queues shared with the Java threads.
    std::mutex eventsMutex;
    std::deque<Event> events;

    std::mutex outgoingMutex;
    std::condition_variable outgoingCondition;
    std::deque<Outgoing> outgoing;
    uint64_t wakeGeneration = 0;

    std::atomic<int> currentStatus { STATUS_INACTIVE };

    // Emulation thread only.
    bool active = false;
    bool isHost = false;
    bool waitingForState = false;
    uint16_t localPort = 0;
    uint32_t inputDelay = 4;
    uint16_t epoch = 0;
    uint32_t frame = 0;
    uint32_t scheduledUntil = 0;  // last frame with local input already sent
    uint8_t activePorts = 1;
    int stalledFrames = 0;
    int framesSinceStateRequest = 0;
    int framesSinceResync = 0;
    std::map<uint32_t, FrameInputs> inputs;
    FrameInputs current {};

    // Host only: our checksums, and the ones players sent for frames we have not run yet.
    std::map<uint32_t, uint32_t> hostChecksums;
    std::multimap<uint32_t, uint32_t> pendingChecksums;

    // Player only: game state being received from the host.
    struct IncomingState {
        uint16_t epoch = 0;
        uint32_t frame = 0;
        uint8_t ports = 0;
        uint32_t rawSize = 0;
        uint32_t compressedSize = 0;
        std::vector<uint8_t> buffer;
        uint32_t received = 0;
    } incomingState;
};

} // namespace libretrodroid

#endif // LIBRETRODROID_NETPLAY_H
