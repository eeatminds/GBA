// Host-side tests for the netpacket bridge. Run with ./run_tests.sh.
#include "../../main/cpp/netpacket.h"

#include <cassert>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

using libretrodroid::NetPacket;

namespace {

// A fake core that records what the frontend tells it.
struct FakeCore {
    int startCount = 0;
    int stopCount = 0;
    int pollCount = 0;
    uint16_t clientId = 0xFFFE;
    lrd_netpacket_send_t send = nullptr;
    lrd_netpacket_poll_receive_t pollReceive = nullptr;
    std::vector<std::pair<uint16_t, std::string>> received;
    std::vector<uint16_t> connected;
    std::vector<uint16_t> disconnected;
    int maxPeers = 3;
    bool echo = false;
};

FakeCore core;

void coreStart(uint16_t clientId, lrd_netpacket_send_t send, lrd_netpacket_poll_receive_t poll) {
    core.startCount++;
    core.clientId = clientId;
    core.send = send;
    core.pollReceive = poll;
}
void coreReceive(const void* buf, size_t len, uint16_t clientId) {
    std::string data(static_cast<const char*>(buf), len);
    core.received.emplace_back(clientId, data);
    // Sending from inside receive must work (gpSP answers packets this way).
    if (core.echo) core.send(1, ("re:" + data).data(), data.size() + 3, clientId);
}
void coreStop() { core.stopCount++; }
void corePoll() { core.pollCount++; }
bool coreConnected(uint16_t clientId) {
    if ((int) core.connected.size() >= core.maxPeers) return false;
    core.connected.push_back(clientId);
    return true;
}
void coreDisconnected(uint16_t clientId) { core.disconnected.push_back(clientId); }

const lrd_netpacket_callback callbacks = {
    coreStart, coreReceive, coreStop, corePoll, coreConnected, coreDisconnected, "fake v1",
};

void push(uint16_t from, const std::string& s) {
    NetPacket::getInstance().pushIncoming(from, reinterpret_cast<const uint8_t*>(s.data()), s.size());
}

std::string take(NetPacket::Outgoing& out) {
    bool ok = NetPacket::getInstance().takeOutgoing(out, 100);
    assert(ok);
    return std::string(out.data.begin(), out.data.end());
}

void testSessionLifecycle() {
    auto& np = NetPacket::getInstance();
    np.reset();
    core = FakeCore();
    assert(!np.isSupported());

    np.setCallback(&callbacks);
    assert(np.isSupported());
    assert(np.protocolVersion() == "fake v1");

    // Data before the session starts is dropped.
    push(1, "early");
    np.onFrame();
    assert(core.startCount == 0 && core.received.empty());

    np.requestStart(0);
    push(1, "hello");
    np.onFrame();
    assert(core.startCount == 1 && core.clientId == 0);
    assert(core.received.size() == 1 && core.received[0].first == 1 && core.received[0].second == "hello");
    assert(core.pollCount == 1);

    // Core sends a broadcast; the Java side takes it with the header fields filled.
    const char msg[] = "abc";
    core.send(1, msg, 3, LRD_NETPACKET_BROADCAST);
    NetPacket::Outgoing out;
    assert(take(out) == "abc");
    assert(out.type == NetPacket::OUTGOING_DATA && out.target == LRD_NETPACKET_BROADCAST);

    // Flush-only sends produce nothing.
    core.send(4, nullptr, 0, 0);
    assert(!np.takeOutgoing(out, 10));

    np.requestStop();
    np.onFrame();
    assert(core.stopCount == 1);
    push(1, "late");
    np.onFrame();
    assert(core.received.size() == 1);
    std::puts("ok - session lifecycle");
}

void testPeersAndReject() {
    auto& np = NetPacket::getInstance();
    np.reset();
    core = FakeCore();
    core.maxPeers = 1;
    np.setCallback(&callbacks);
    np.requestStart(0);
    np.pushPeerConnected(1);
    np.pushPeerConnected(2);
    np.pushPeerDisconnected(1);
    np.onFrame();
    assert(core.connected.size() == 1 && core.connected[0] == 1);
    assert(core.disconnected.size() == 1 && core.disconnected[0] == 1);

    NetPacket::Outgoing out;
    assert(np.takeOutgoing(out, 100));
    assert(out.type == NetPacket::OUTGOING_REJECT_PEER && out.target == 2 && out.data.empty());
    std::puts("ok - peers and reject");
}

void testPollReceiveAndEcho() {
    auto& np = NetPacket::getInstance();
    np.reset();
    core = FakeCore();
    core.echo = true;
    np.setCallback(&callbacks);
    np.requestStart(2);
    np.onFrame();
    assert(core.clientId == 2);

    // The core may poll for packets in the middle of a frame.
    push(0, "ping");
    core.pollReceive();
    assert(core.received.size() == 1 && core.received[0].second == "ping");
    NetPacket::Outgoing out;
    assert(take(out) == "re:ping" && out.target == 0);

    // A second start request is never processed from inside the core's poll.
    np.requestStop();
    np.requestStart(3);
    core.pollReceive();
    assert(core.stopCount == 1 && core.startCount == 1);
    np.onFrame();
    assert(core.startCount == 2 && core.clientId == 3);
    std::puts("ok - poll receive and echo");
}

void testWakeAndReset() {
    auto& np = NetPacket::getInstance();
    np.reset();
    core = FakeCore();
    np.setCallback(&callbacks);
    np.requestStart(0);
    np.onFrame();

    // A blocked taker returns promptly when woken.
    std::thread waker([] {
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        NetPacket::getInstance().wakeTakers();
    });
    auto begin = std::chrono::steady_clock::now();
    NetPacket::Outgoing out;
    bool got = np.takeOutgoing(out, 5000);
    auto elapsed = std::chrono::steady_clock::now() - begin;
    waker.join();
    assert(!got);
    assert(elapsed < std::chrono::seconds(2));

    // Reset stops the session and forgets the core.
    const char msg[] = "x";
    core.send(1, msg, 1, 0);
    np.reset();
    assert(core.stopCount == 1);
    assert(!np.isSupported());
    assert(!np.takeOutgoing(out, 10));
    std::puts("ok - wake and reset");
}

void testOversizedPacketsAreDropped() {
    auto& np = NetPacket::getInstance();
    np.reset();
    core = FakeCore();
    np.setCallback(&callbacks);
    np.requestStart(0);
    np.onFrame();
    std::vector<uint8_t> big(64 * 1024 + 1, 7);
    np.pushIncoming(1, big.data(), big.size());
    core.send(1, big.data(), big.size(), 1);
    np.onFrame();
    NetPacket::Outgoing out;
    assert(core.received.empty());
    assert(!np.takeOutgoing(out, 10));
    std::puts("ok - oversized packets dropped");
}

} // namespace

int main() {
    testSessionLifecycle();
    testPeersAndReject();
    testPollReceiveAndEcho();
    testWakeAndReset();
    testOversizedPacketsAreDropped();
    std::puts("all netpacket tests passed");
    return 0;
}
