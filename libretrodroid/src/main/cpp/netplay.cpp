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

#include "netplay.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <utility>
#include <zlib.h>

#include "libretro.h"
#include "log.h"

namespace libretrodroid {

namespace {

constexpr size_t INPUT_MESSAGE_SIZE = 18;
constexpr size_t CHECKSUM_MESSAGE_SIZE = 11;
constexpr size_t STATE_HEADER_SIZE = 20;
constexpr size_t MAX_QUEUED = 16384;
constexpr size_t MAX_MESSAGE_SIZE = 70 * 1024;
constexpr uint32_t MAX_STATE_SIZE = 64 * 1024 * 1024;
constexpr uint32_t MAX_FRAMES_AHEAD = 600;
constexpr int STATE_REQUEST_INTERVAL = 300;
constexpr int MIN_FRAMES_BETWEEN_RESYNCS = 120;

void putU16(std::vector<uint8_t>& out, uint16_t value) {
    out.push_back(value & 0xFF);
    out.push_back((value >> 8) & 0xFF);
}

void putU32(std::vector<uint8_t>& out, uint32_t value) {
    for (int i = 0; i < 4; i++) out.push_back((value >> (8 * i)) & 0xFF);
}

uint16_t getU16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}

uint32_t getU32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

} // namespace

// ---------------------------------------------------------------------------
// Java side

void Netplay::requestStart(uint16_t localClientId, int inputDelayFrames) {
    int delay = std::clamp(inputDelayFrames, 1, 15);
    pushEvent(Event { EVENT_START, localClientId, delay, {} });
}

void Netplay::requestStop() {
    pushEvent(Event { EVENT_STOP, 0, 0, {} });
}

void Netplay::pushIncoming(uint16_t fromClientId, const uint8_t* data, size_t size) {
    if (size == 0 || size > MAX_MESSAGE_SIZE) return;
    pushEvent(Event { EVENT_DATA, fromClientId, 0, std::vector<uint8_t>(data, data + size) });
}

void Netplay::pushPeerJoined(uint16_t clientId) {
    pushEvent(Event { EVENT_JOINED, clientId, 0, {} });
}

void Netplay::pushPeerLeft(uint16_t clientId) {
    pushEvent(Event { EVENT_LEFT, clientId, 0, {} });
}

void Netplay::pushEvent(Event event) {
    std::lock_guard<std::mutex> lock(eventsMutex);
    if (events.size() >= MAX_QUEUED) {
        LOGW("Netplay incoming queue is full, dropping oldest event");
        events.pop_front();
    }
    events.push_back(std::move(event));
}

void Netplay::send(uint16_t target, std::vector<uint8_t> data) {
    {
        std::lock_guard<std::mutex> lock(outgoingMutex);
        if (outgoing.size() >= MAX_QUEUED) {
            LOGW("Netplay outgoing queue is full, dropping oldest packet");
            outgoing.pop_front();
        }
        outgoing.push_back(Outgoing { target, std::move(data) });
    }
    outgoingCondition.notify_one();
}

bool Netplay::takeOutgoing(Outgoing& result, int timeoutMs) {
    std::unique_lock<std::mutex> lock(outgoingMutex);
    uint64_t generation = wakeGeneration;
    outgoingCondition.wait_for(lock, std::chrono::milliseconds(timeoutMs), [&] {
        return !outgoing.empty() || wakeGeneration != generation;
    });
    if (outgoing.empty()) return false;
    result = std::move(outgoing.front());
    outgoing.pop_front();
    return true;
}

void Netplay::wakeTakers() {
    {
        std::lock_guard<std::mutex> lock(outgoingMutex);
        wakeGeneration++;
    }
    outgoingCondition.notify_all();
}

void Netplay::updateStatus(Status status) {
    currentStatus.store(status);
}

// ---------------------------------------------------------------------------
// Emulation thread

void Netplay::processEvents(const Hooks& hooks) {
    while (true) {
        Event event;
        {
            std::lock_guard<std::mutex> lock(eventsMutex);
            if (events.empty()) return;
            event = std::move(events.front());
            events.pop_front();
        }

        switch (event.type) {
            case EVENT_START:
                if (event.clientId >= MAX_PLAYERS) {
                    LOGE("Netplay: client id %u has no controller port", event.clientId);
                    break;
                }
                active = true;
                isHost = event.clientId == 0;
                localPort = event.clientId;
                inputDelay = static_cast<uint32_t>(event.value);
                epoch = 0;
                incomingState = IncomingState();
                framesSinceStateRequest = 0;
                framesSinceResync = 0;
                if (isHost) {
                    activePorts = 1;
                    waitingForState = false;
                    restartAt(0);
                    updateStatus(STATUS_RUNNING);
                } else {
                    activePorts = 0;
                    waitingForState = true;
                    inputs.clear();
                    send(0, { MSG_REQUEST_STATE });
                    updateStatus(STATUS_WAITING_FOR_HOST);
                }
                LOGI("Netplay started as player %u (delay %u)", localPort, inputDelay);
                break;

            case EVENT_STOP:
                active = false;
                waitingForState = false;
                inputs.clear();
                updateStatus(STATUS_INACTIVE);
                break;

            case EVENT_DATA:
                if (active) handleData(event.clientId, event.data, hooks);
                break;

            case EVENT_JOINED:
                if (active && isHost && event.clientId < MAX_PLAYERS) {
                    activePorts |= (1 << event.clientId);
                    resync(hooks);
                }
                break;

            case EVENT_LEFT:
                if (active && isHost && event.clientId < MAX_PLAYERS) {
                    activePorts &= ~(1 << event.clientId);
                    resync(hooks);
                }
                break;
        }
    }
}

void Netplay::restartAt(uint32_t startFrame) {
    frame = startFrame;
    inputs.clear();
    // Nobody has pressed anything yet for the first frames after a (re)start.
    for (uint32_t f = startFrame; f < startFrame + inputDelay; f++) {
        FrameInputs empty;
        empty.received = 0xFF;
        inputs[f] = empty;
    }
    scheduledUntil = startFrame + inputDelay - 1;
    stalledFrames = 0;
    hostChecksums.clear();
    pendingChecksums.clear();
}

void Netplay::resync(const Hooks& hooks) {
    epoch++;
    restartAt(frame);
    framesSinceResync = 0;

    if ((activePorts & ~1) == 0) return;  // Nobody else to send it to.

    std::vector<uint8_t> raw = hooks.serialize();
    if (raw.empty()) {
        LOGE("Netplay: core returned an empty state");
        return;
    }

    uLongf compressedSize = compressBound(raw.size());
    std::vector<uint8_t> compressed(compressedSize);
    if (compress2(compressed.data(), &compressedSize, raw.data(), raw.size(), Z_BEST_SPEED) != Z_OK) {
        LOGE("Netplay: could not compress the game state");
        return;
    }
    compressed.resize(compressedSize);

    LOGI("Netplay: sending state for frame %u (%zu bytes, %zu compressed)", frame, raw.size(), compressed.size());
    for (size_t offset = 0; offset < compressed.size(); offset += STATE_CHUNK_SIZE) {
        size_t length = std::min(STATE_CHUNK_SIZE, compressed.size() - offset);
        std::vector<uint8_t> message;
        message.reserve(STATE_HEADER_SIZE + length);
        message.push_back(MSG_STATE_CHUNK);
        putU16(message, epoch);
        putU32(message, frame);
        message.push_back(activePorts);
        putU32(message, static_cast<uint32_t>(raw.size()));
        putU32(message, static_cast<uint32_t>(compressed.size()));
        putU32(message, static_cast<uint32_t>(offset));
        message.insert(message.end(), compressed.begin() + offset, compressed.begin() + offset + length);
        send(BROADCAST, std::move(message));
    }
}

void Netplay::handleData(uint16_t from, const std::vector<uint8_t>& data, const Hooks& hooks) {
    if (data.empty()) return;
    const uint8_t* p = data.data();

    switch (p[0]) {
        case MSG_INPUT: {
            if (data.size() != INPUT_MESSAGE_SIZE || waitingForState) return;
            uint16_t messageEpoch = getU16(p + 1);
            uint32_t messageFrame = getU32(p + 3);
            uint8_t port = p[7];
            // Inputs from before the last resync, or for someone else's port, are ignored.
            if (messageEpoch != epoch || port != from || port >= MAX_PLAYERS || port == localPort) return;
            if (messageFrame < frame || messageFrame > frame + MAX_FRAMES_AHEAD) return;

            PortInput input;
            input.buttons = getU16(p + 8);
            for (int i = 0; i < 4; i++) input.analog[i] = static_cast<int16_t>(getU16(p + 10 + 2 * i));
            FrameInputs& frameInputs = inputs[messageFrame];
            frameInputs.ports[port] = input;
            frameInputs.received |= (1 << port);
            break;
        }

        case MSG_STATE_CHUNK:
            if (!isHost && from == 0) handleStateChunk(data, hooks);
            break;

        case MSG_CHECKSUM:
            if (isHost && data.size() == CHECKSUM_MESSAGE_SIZE && getU16(p + 1) == epoch) {
                compareChecksum(getU32(p + 3), getU32(p + 7), hooks);
            }
            break;

        case MSG_REQUEST_STATE:
            if (isHost && from > 0 && from < MAX_PLAYERS) {
                bool isNew = (activePorts & (1 << from)) == 0;
                activePorts |= (1 << from);
                // A state is probably already on its way if we just sent one.
                if (isNew || framesSinceResync >= MIN_FRAMES_BETWEEN_RESYNCS) resync(hooks);
            }
            break;

        default:
            break;
    }
}

void Netplay::handleStateChunk(const std::vector<uint8_t>& data, const Hooks& hooks) {
    if (data.size() < STATE_HEADER_SIZE) return;
    const uint8_t* p = data.data();
    uint16_t stateEpoch = getU16(p + 1);
    uint32_t stateFrame = getU32(p + 3);
    uint8_t ports = p[7];
    uint32_t rawSize = getU32(p + 8);
    uint32_t compressedSize = getU32(p + 12);
    uint32_t offset = getU32(p + 16);
    size_t length = data.size() - STATE_HEADER_SIZE;

    if (rawSize == 0 || rawSize > MAX_STATE_SIZE || compressedSize == 0 || compressedSize > MAX_STATE_SIZE) return;

    if (stateEpoch != incomingState.epoch || stateFrame != incomingState.frame ||
        compressedSize != incomingState.compressedSize || incomingState.buffer.empty()) {
        incomingState = IncomingState();
        incomingState.epoch = stateEpoch;
        incomingState.frame = stateFrame;
        incomingState.ports = ports;
        incomingState.rawSize = rawSize;
        incomingState.compressedSize = compressedSize;
        incomingState.buffer.resize(compressedSize);
    }

    if (static_cast<uint64_t>(offset) + length > incomingState.compressedSize) return;
    std::memcpy(incomingState.buffer.data() + offset, p + STATE_HEADER_SIZE, length);
    incomingState.received += length;
    if (incomingState.received < incomingState.compressedSize) return;

    std::vector<uint8_t> raw(incomingState.rawSize);
    uLongf rawLength = raw.size();
    int result = uncompress(raw.data(), &rawLength, incomingState.buffer.data(), incomingState.buffer.size());
    IncomingState completed = std::move(incomingState);
    incomingState = IncomingState();

    if (result != Z_OK || rawLength != raw.size()) {
        LOGE("Netplay: could not decompress the host's game state");
        return;
    }
    if (!hooks.unserialize(raw.data(), raw.size())) {
        // Usually means a different game or core version than the host.
        LOGE("Netplay: could not load the host's game state");
        return;
    }

    epoch = completed.epoch;
    activePorts = completed.ports | (1 << localPort);
    restartAt(completed.frame);
    waitingForState = false;
    framesSinceStateRequest = 0;
    updateStatus(STATUS_RUNNING);
    LOGI("Netplay: loaded host state at frame %u", completed.frame);
}

Netplay::PortInput Netplay::captureLocalInput(const Hooks& hooks) const {
    PortInput input;
    for (unsigned id = 0; id < 16; id++) {
        if (hooks.localInput(RETRO_DEVICE_JOYPAD, 0, id)) input.buttons |= (1 << id);
    }
    input.analog[0] = hooks.localInput(RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_LEFT, RETRO_DEVICE_ID_ANALOG_X);
    input.analog[1] = hooks.localInput(RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_LEFT, RETRO_DEVICE_ID_ANALOG_Y);
    input.analog[2] = hooks.localInput(RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_RIGHT, RETRO_DEVICE_ID_ANALOG_X);
    input.analog[3] = hooks.localInput(RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_RIGHT, RETRO_DEVICE_ID_ANALOG_Y);
    return input;
}

void Netplay::sendLocalInput(uint32_t inputFrame, const PortInput& input) {
    std::vector<uint8_t> message;
    message.reserve(INPUT_MESSAGE_SIZE);
    message.push_back(MSG_INPUT);
    putU16(message, epoch);
    putU32(message, inputFrame);
    message.push_back(static_cast<uint8_t>(localPort));
    putU16(message, input.buttons);
    for (int16_t axis : input.analog) putU16(message, static_cast<uint16_t>(axis));
    send(BROADCAST, std::move(message));
}

void Netplay::compareChecksum(uint32_t checkFrame, uint32_t checksum, const Hooks& hooks) {
    auto ours = hostChecksums.find(checkFrame);
    if (ours != hostChecksums.end()) {
        if (ours->second != checksum && framesSinceResync >= MIN_FRAMES_BETWEEN_RESYNCS) {
            LOGW("Netplay: players out of sync at frame %u, resending state", checkFrame);
            resync(hooks);
        }
        return;
    }
    if (checkFrame >= frame && pendingChecksums.size() < 64) {
        pendingChecksums.emplace(checkFrame, checksum);
    }
}

bool Netplay::beginFrame(const Hooks& hooks) {
    processEvents(hooks);
    if (!active) return true;

    if (waitingForState) {
        if (++framesSinceStateRequest >= STATE_REQUEST_INTERVAL) {
            framesSinceStateRequest = 0;
            send(0, { MSG_REQUEST_STATE });
        }
        return false;
    }

    // Send our input for the frame `inputDelay` steps ahead, once per frame.
    uint32_t target = frame + inputDelay;
    while (scheduledUntil < target) {
        scheduledUntil++;
        PortInput input = captureLocalInput(hooks);
        FrameInputs& frameInputs = inputs[scheduledUntil];
        frameInputs.ports[localPort] = input;
        frameInputs.received |= (1 << localPort);
        sendLocalInput(scheduledUntil, input);
    }

    auto it = inputs.find(frame);
    if (it == inputs.end() || (it->second.received & activePorts) != activePorts) {
        if (++stalledFrames >= STALL_FRAMES_BEFORE_WAITING) updateStatus(STATUS_WAITING_FOR_PLAYERS);
        return false;
    }

    stalledFrames = 0;
    updateStatus(STATUS_RUNNING);
    current = it->second;
    return true;
}

void Netplay::endFrame(const Hooks& hooks) {
    if (!active || waitingForState) return;

    inputs.erase(frame);
    uint32_t ranFrame = frame;
    frame++;
    framesSinceResync++;

    bool othersPresent = (activePorts & ~(1 << localPort)) != 0;
    if (!othersPresent || ranFrame % CHECKSUM_INTERVAL != 0) return;

    uint32_t checksum = hooks.memoryChecksum();
    if (isHost) {
        hostChecksums[ranFrame] = checksum;
        while (hostChecksums.size() > 32) hostChecksums.erase(hostChecksums.begin());

        bool mismatch = false;
        auto range = pendingChecksums.equal_range(ranFrame);
        for (auto it = range.first; it != range.second; ++it) {
            if (it->second != checksum) mismatch = true;
        }
        pendingChecksums.erase(pendingChecksums.begin(), pendingChecksums.upper_bound(ranFrame));
        if (mismatch && framesSinceResync >= MIN_FRAMES_BETWEEN_RESYNCS) {
            LOGW("Netplay: players out of sync at frame %u, resending state", ranFrame);
            resync(hooks);
        }
    } else {
        std::vector<uint8_t> message;
        message.reserve(CHECKSUM_MESSAGE_SIZE);
        message.push_back(MSG_CHECKSUM);
        putU16(message, epoch);
        putU32(message, ranFrame);
        putU32(message, checksum);
        send(0, std::move(message));
    }
}

int16_t Netplay::inputState(unsigned port, unsigned device, unsigned index, unsigned id) const {
    if (port >= MAX_PLAYERS) return 0;
    const PortInput& input = current.ports[port];

    switch (device & RETRO_DEVICE_MASK) {
        case RETRO_DEVICE_JOYPAD:
            if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return static_cast<int16_t>(input.buttons);
            return id < 16 ? ((input.buttons >> id) & 1) : 0;

        case RETRO_DEVICE_ANALOG:
            if (index == RETRO_DEVICE_INDEX_ANALOG_LEFT && id <= RETRO_DEVICE_ID_ANALOG_Y) return input.analog[id];
            if (index == RETRO_DEVICE_INDEX_ANALOG_RIGHT && id <= RETRO_DEVICE_ID_ANALOG_Y) return input.analog[2 + id];
            if (index == RETRO_DEVICE_INDEX_ANALOG_BUTTON && id < 16) return ((input.buttons >> id) & 1) ? 0x7FFF : 0;
            return 0;

        default:
            // Pointers, mice and light guns are not synced.
            return 0;
    }
}

void Netplay::reset() {
    active = false;
    waitingForState = false;
    inputs.clear();
    hostChecksums.clear();
    pendingChecksums.clear();
    incomingState = IncomingState();
    {
        std::lock_guard<std::mutex> lock(eventsMutex);
        events.clear();
    }
    {
        std::lock_guard<std::mutex> lock(outgoingMutex);
        outgoing.clear();
    }
    wakeTakers();
    updateStatus(STATUS_INACTIVE);
}

} // namespace libretrodroid
