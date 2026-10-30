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

#ifndef LIBRETRODROID_NETPACKET_H
#define LIBRETRODROID_NETPACKET_H

#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

// The bundled libretro.h predates the current netpacket interface (environment 78),
// so we keep our own copy of its definitions. The layout matches libretro.h exactly.
#define LRD_ENVIRONMENT_SET_NETPACKET_INTERFACE 78
#define LRD_NETPACKET_BROADCAST 0xFFFF

typedef void (*lrd_netpacket_send_t)(int flags, const void* buf, size_t len, uint16_t client_id);
typedef void (*lrd_netpacket_poll_receive_t)();
typedef void (*lrd_netpacket_start_t)(
    uint16_t client_id,
    lrd_netpacket_send_t send_fn,
    lrd_netpacket_poll_receive_t poll_receive_fn
);
typedef void (*lrd_netpacket_receive_t)(const void* buf, size_t len, uint16_t client_id);
typedef void (*lrd_netpacket_stop_t)(void);
typedef void (*lrd_netpacket_poll_t)(void);
typedef bool (*lrd_netpacket_connected_t)(uint16_t client_id);
typedef void (*lrd_netpacket_disconnected_t)(uint16_t client_id);

struct lrd_netpacket_callback {
    lrd_netpacket_start_t start;
    lrd_netpacket_receive_t receive;
    lrd_netpacket_stop_t stop;
    lrd_netpacket_poll_t poll;
    lrd_netpacket_connected_t connected;
    lrd_netpacket_disconnected_t disconnected;
    const char* protocol_version;
};

namespace libretrodroid {

// Bridges a core's netpacket interface (used by gpSP for link cable and wireless adapter
// emulation) to the Java side, which moves the packets over the network.
//
// All calls into the core happen on the emulation thread, from onFrame() or from the
// core's own poll_receive call. The Java side only touches the thread-safe queues.
class NetPacket {
public:
    static NetPacket& getInstance() {
        static NetPacket instance;
        return instance;
    }
    NetPacket(NetPacket const&) = delete;
    void operator=(NetPacket const&) = delete;

    enum OutgoingType : uint8_t {
        OUTGOING_DATA = 0,
        // The core refused a newly connected player (e.g. room is full for this link mode).
        OUTGOING_REJECT_PEER = 1,
    };

    struct Outgoing {
        uint8_t type;
        uint16_t target;
        std::vector<uint8_t> data;
    };

    // Called from the environment callback when the core registers its interface.
    void setCallback(const lrd_netpacket_callback* callback);
    bool isSupported();
    std::string protocolVersion();

    // Thread-safe, called from Java.
    void requestStart(uint16_t localClientId);
    void requestStop();
    void pushIncoming(uint16_t fromClientId, const uint8_t* data, size_t size);
    void pushPeerConnected(uint16_t clientId);
    void pushPeerDisconnected(uint16_t clientId);
    bool takeOutgoing(Outgoing& result, int timeoutMs);
    void wakeTakers();

    // Emulation thread: called before every retro_run.
    void onFrame();

    // Emulation thread: called before the game is unloaded.
    void reset();

private:
    NetPacket() = default;

    enum EventType : uint8_t {
        EVENT_START,
        EVENT_STOP,
        EVENT_DATA,
        EVENT_PEER_CONNECTED,
        EVENT_PEER_DISCONNECTED,
    };

    struct Event {
        EventType type;
        uint16_t clientId;
        std::vector<uint8_t> data;
    };

    static void sendFn(int flags, const void* buf, size_t len, uint16_t clientId);
    static void pollReceiveFn();

    void processEvents(bool allowStart);
    void pushEvent(Event event);
    void pushOutgoing(Outgoing outgoing);

    static constexpr size_t MAX_PACKET_SIZE = 64 * 1024;
    static constexpr size_t MAX_QUEUED_OUTGOING = 8192;
    static constexpr size_t MAX_QUEUED_EVENTS = 8192;

    std::mutex callbackMutex;
    bool hasCallback = false;
    lrd_netpacket_callback callback {};

    // Only touched from the emulation thread.
    bool started = false;

    std::mutex eventsMutex;
    std::deque<Event> events;

    std::mutex outgoingMutex;
    std::condition_variable outgoingCondition;
    std::deque<Outgoing> outgoing;
    uint64_t wakeGeneration = 0;
};

} // namespace libretrodroid

#endif // LIBRETRODROID_NETPACKET_H
