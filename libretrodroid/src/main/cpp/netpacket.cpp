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

#include "netpacket.h"

#include <chrono>
#include <utility>

#include "log.h"

namespace libretrodroid {

void NetPacket::setCallback(const lrd_netpacket_callback* newCallback) {
    std::lock_guard<std::mutex> lock(callbackMutex);
    if (newCallback == nullptr || newCallback->start == nullptr || newCallback->receive == nullptr) {
        hasCallback = false;
        callback = {};
        return;
    }
    callback = *newCallback;
    hasCallback = true;
    LOGI("Core registered netpacket interface (protocol: %s)",
         callback.protocol_version ? callback.protocol_version : "none");
}

bool NetPacket::isSupported() {
    std::lock_guard<std::mutex> lock(callbackMutex);
    return hasCallback;
}

std::string NetPacket::protocolVersion() {
    std::lock_guard<std::mutex> lock(callbackMutex);
    if (!hasCallback || callback.protocol_version == nullptr) return {};
    return callback.protocol_version;
}

void NetPacket::requestStart(uint16_t localClientId) {
    pushEvent(Event { EVENT_START, localClientId, {} });
}

void NetPacket::requestStop() {
    pushEvent(Event { EVENT_STOP, 0, {} });
}

void NetPacket::pushIncoming(uint16_t fromClientId, const uint8_t* data, size_t size) {
    if (size == 0 || size > MAX_PACKET_SIZE) return;
    pushEvent(Event { EVENT_DATA, fromClientId, std::vector<uint8_t>(data, data + size) });
}

void NetPacket::pushPeerConnected(uint16_t clientId) {
    pushEvent(Event { EVENT_PEER_CONNECTED, clientId, {} });
}

void NetPacket::pushPeerDisconnected(uint16_t clientId) {
    pushEvent(Event { EVENT_PEER_DISCONNECTED, clientId, {} });
}

void NetPacket::pushEvent(Event event) {
    std::lock_guard<std::mutex> lock(eventsMutex);
    if (events.size() >= MAX_QUEUED_EVENTS) {
        LOGW("Netpacket incoming queue is full, dropping oldest event");
        events.pop_front();
    }
    events.push_back(std::move(event));
}

void NetPacket::pushOutgoing(Outgoing message) {
    {
        std::lock_guard<std::mutex> lock(outgoingMutex);
        if (outgoing.size() >= MAX_QUEUED_OUTGOING) {
            LOGW("Netpacket outgoing queue is full, dropping oldest packet");
            outgoing.pop_front();
        }
        outgoing.push_back(std::move(message));
    }
    outgoingCondition.notify_one();
}

bool NetPacket::takeOutgoing(Outgoing& result, int timeoutMs) {
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

void NetPacket::wakeTakers() {
    {
        std::lock_guard<std::mutex> lock(outgoingMutex);
        wakeGeneration++;
    }
    outgoingCondition.notify_all();
}

void NetPacket::sendFn(int flags, const void* buf, size_t len, uint16_t clientId) {
    // A null or empty buffer only asks to flush; we always send immediately.
    if (buf == nullptr || len == 0) return;
    if (len > MAX_PACKET_SIZE) {
        LOGE("Netpacket of %zu bytes is too large, dropping it", len);
        return;
    }
    auto bytes = static_cast<const uint8_t*>(buf);
    getInstance().pushOutgoing(Outgoing { OUTGOING_DATA, clientId, std::vector<uint8_t>(bytes, bytes + len) });
}

void NetPacket::pollReceiveFn() {
    getInstance().processEvents(false);
}

void NetPacket::processEvents(bool allowStart) {
    lrd_netpacket_callback cb {};
    {
        std::lock_guard<std::mutex> lock(callbackMutex);
        if (!hasCallback) return;
        cb = callback;
    }

    while (true) {
        Event event;
        {
            std::lock_guard<std::mutex> lock(eventsMutex);
            if (events.empty()) return;
            // A session can only start between frames, never from inside the core's own poll.
            if (events.front().type == EVENT_START && !allowStart) return;
            event = std::move(events.front());
            events.pop_front();
        }

        switch (event.type) {
            case EVENT_START:
                if (started) break;
                started = true;
                LOGI("Starting netpacket session as client %u", event.clientId);
                cb.start(event.clientId, &NetPacket::sendFn, &NetPacket::pollReceiveFn);
                break;

            case EVENT_STOP:
                if (!started) break;
                started = false;
                LOGI("Stopping netpacket session");
                if (cb.stop) cb.stop();
                break;

            case EVENT_DATA:
                if (started) cb.receive(event.data.data(), event.data.size(), event.clientId);
                break;

            case EVENT_PEER_CONNECTED: {
                if (!started) break;
                bool accepted = cb.connected ? cb.connected(event.clientId) : true;
                if (!accepted) {
                    LOGI("Core refused player %u", event.clientId);
                    pushOutgoing(Outgoing { OUTGOING_REJECT_PEER, event.clientId, {} });
                }
                break;
            }

            case EVENT_PEER_DISCONNECTED:
                if (started && cb.disconnected) cb.disconnected(event.clientId);
                break;
        }
    }
}

void NetPacket::onFrame() {
    processEvents(true);

    if (!started) return;

    lrd_netpacket_poll_t poll = nullptr;
    {
        std::lock_guard<std::mutex> lock(callbackMutex);
        if (hasCallback) poll = callback.poll;
    }
    if (poll) poll();
}

void NetPacket::reset() {
    lrd_netpacket_stop_t stop = nullptr;
    {
        std::lock_guard<std::mutex> lock(callbackMutex);
        if (hasCallback) stop = callback.stop;
        hasCallback = false;
        callback = {};
    }
    if (started && stop) stop();
    started = false;

    {
        std::lock_guard<std::mutex> lock(eventsMutex);
        events.clear();
    }
    {
        std::lock_guard<std::mutex> lock(outgoingMutex);
        outgoing.clear();
    }
    wakeTakers();
}

} // namespace libretrodroid
