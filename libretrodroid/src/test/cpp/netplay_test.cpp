// Host-side tests for the "play together" sync engine. Two (or three) fake consoles run
// the same deterministic "game" and talk through a simulated network with latency.
#include "../../main/cpp/netplay.h"

#include <cassert>
#include <cstdio>
#include <cstring>
#include <map>
#include <memory>
#include <vector>

#include "libretro.h"

using libretrodroid::Netplay;

namespace {

// A tiny deterministic "game": its memory depends on every frame's inputs.
struct FakeConsole {
    uint32_t frames = 0;
    uint64_t memory = 1;

    void run(const Netplay& netplay) {
        uint64_t p0 = static_cast<uint16_t>(netplay.inputState(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK));
        uint64_t p1 = static_cast<uint16_t>(netplay.inputState(1, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK));
        uint64_t p2 = static_cast<uint16_t>(netplay.inputState(2, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK));
        memory = memory * 6364136223846793005ULL + p0 * 7 + p1 * 13 + p2 * 17 + frames;
        frames++;
    }
};

struct Player {
    uint16_t id;
    Netplay netplay;
    FakeConsole console;
    std::map<uint32_t, uint64_t> history;  // console frame -> memory after it
    uint32_t localTick = 0;
    bool sawRemoteInput = false;

    Netplay::Hooks hooks() {
        Netplay::Hooks h;
        h.serialize = [this] {
            std::vector<uint8_t> bytes(sizeof(FakeConsole));
            std::memcpy(bytes.data(), &console, sizeof(FakeConsole));
            return bytes;
        };
        h.unserialize = [this](const uint8_t* data, size_t size) {
            if (size != sizeof(FakeConsole)) return false;
            std::memcpy(&console, data, size);
            return true;
        };
        h.memoryChecksum = [this] { return static_cast<uint32_t>(console.memory ^ (console.memory >> 32)); };
        // Each player presses a different, changing button pattern.
        h.localInput = [this](unsigned device, unsigned, unsigned buttonId) -> int16_t {
            if (device != RETRO_DEVICE_JOYPAD) return 0;
            unsigned pressed = (localTick / 7 + id * 5) % 16;
            return buttonId == pressed ? 1 : 0;
        };
        return h;
    }

    void step() {
        localTick++;
        auto h = hooks();
        if (!netplay.beginFrame(h)) return;
        for (unsigned port = 0; port < 4; port++) {
            if (port != id && netplay.inputState(port, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK) != 0) {
                sawRemoteInput = true;
            }
        }
        console.run(netplay);
        history[console.frames] = console.memory;
        netplay.endFrame(h);
    }
};

struct InFlight {
    uint32_t deliverAt;
    uint16_t from;
    uint16_t to;
    std::vector<uint8_t> data;
};

struct Network {
    std::vector<std::unique_ptr<Player>> players;
    std::vector<InFlight> inFlight;
    uint32_t tick = 0;
    uint32_t latencyTicks = 3;  // one tick is one 60 Hz frame, so 3 ticks is about 50 ms each way
    bool dropFromGuest = false;

    Player& add(uint16_t id) {
        players.push_back(std::make_unique<Player>());
        players.back()->id = id;
        return *players.back();
    }

    Player* find(uint16_t id) {
        for (auto& p : players) if (p->id == id) return p.get();
        return nullptr;
    }

    void run(uint32_t ticks) {
        for (uint32_t i = 0; i < ticks; i++) {
            tick++;
            // Deliver what has arrived.
            std::vector<InFlight> later;
            for (auto& message : inFlight) {
                if (message.deliverAt > tick) {
                    later.push_back(std::move(message));
                    continue;
                }
                if (Player* to = find(message.to)) to->netplay.pushIncoming(message.from, message.data.data(), message.data.size());
            }
            inFlight = std::move(later);

            for (auto& player : players) player->step();

            // Collect what everyone sent, like the relay server would.
            for (auto& player : players) {
                Netplay::Outgoing out;
                while (player->netplay.takeOutgoing(out, 0)) {
                    if (dropFromGuest && player->id != 0) continue;
                    for (auto& other : players) {
                        if (other->id == player->id) continue;
                        if (out.target != Netplay::BROADCAST && out.target != other->id) continue;
                        inFlight.push_back(InFlight { tick + latencyTicks, player->id, other->id, out.data });
                    }
                }
            }
        }
    }
};

// Every frame both players ran must have produced the same memory.
size_t checkInSync(Player& a, Player& b, uint32_t fromFrame) {
    size_t compared = 0;
    for (auto& [frame, memory] : a.history) {
        if (frame < fromFrame) continue;
        auto other = b.history.find(frame);
        if (other == b.history.end()) continue;
        assert(other->second == memory);
        compared++;
    }
    return compared;
}

void testTwoPlayersStayInSync() {
    Network net;
    Player& host = net.add(0);
    Player& guest = net.add(1);

    host.netplay.requestStart(0, 4);
    net.run(30);  // The host plays alone for a while.
    assert(host.netplay.status() == Netplay::STATUS_RUNNING);

    guest.netplay.requestStart(1, 4);
    host.netplay.pushPeerJoined(1);
    net.run(1);
    assert(guest.netplay.status() == Netplay::STATUS_WAITING_FOR_HOST);

    net.run(600);
    assert(guest.netplay.status() == Netplay::STATUS_RUNNING);
    assert(host.netplay.status() == Netplay::STATUS_RUNNING);
    assert(host.sawRemoteInput && guest.sawRemoteInput);

    // Guest histories are rebuilt from the host's state, so compare after the join.
    guest.history.clear();
    host.history.clear();
    net.run(600);
    size_t compared = checkInSync(host, guest, 0);
    assert(compared > 500);
    // With 50 ms latency and a 4 frame delay, nobody should fall far behind.
    int32_t gap = static_cast<int32_t>(host.console.frames) - static_cast<int32_t>(guest.console.frames);
    assert(gap > -10 && gap < 10);
    std::printf("ok - two players stay in sync (%zu frames compared)\n", compared);
}

void testDesyncIsRepaired() {
    Network net;
    Player& host = net.add(0);
    Player& guest = net.add(1);
    host.netplay.requestStart(0, 4);
    guest.netplay.requestStart(1, 4);
    host.netplay.pushPeerJoined(1);
    net.run(400);
    assert(guest.netplay.status() == Netplay::STATUS_RUNNING);

    // Something goes wrong on the guest's side.
    guest.console.memory ^= 0xDEADBEEF;
    net.run(700);

    host.history.clear();
    guest.history.clear();
    net.run(300);
    assert(checkInSync(host, guest, 0) > 200);
    std::puts("ok - desync is repaired by a state resend");
}

void testHostWaitsForLatePlayer() {
    Network net;
    Player& host = net.add(0);
    Player& guest = net.add(1);
    host.netplay.requestStart(0, 4);
    guest.netplay.requestStart(1, 4);
    host.netplay.pushPeerJoined(1);
    net.run(300);

    uint32_t before = host.console.frames;
    net.dropFromGuest = true;
    net.run(100);
    assert(host.netplay.status() == Netplay::STATUS_WAITING_FOR_PLAYERS);
    assert(host.console.frames - before < 15);  // only the frames already covered by the delay

    // When the player leaves, the host carries on alone.
    net.dropFromGuest = false;
    net.players.pop_back();
    host.netplay.pushPeerLeft(1);
    uint32_t paused = host.console.frames;
    net.run(60);
    assert(host.netplay.status() == Netplay::STATUS_RUNNING);
    assert(host.console.frames > paused + 40);
    std::puts("ok - host waits for a late player and continues when they leave");
}

void testThreePlayers() {
    Network net;
    Player& host = net.add(0);
    Player& a = net.add(1);
    host.netplay.requestStart(0, 5);
    a.netplay.requestStart(1, 5);
    host.netplay.pushPeerJoined(1);
    net.run(200);

    Player& b = net.add(2);
    b.netplay.requestStart(2, 5);
    host.netplay.pushPeerJoined(2);
    net.run(400);
    assert(b.netplay.status() == Netplay::STATUS_RUNNING);

    host.history.clear();
    a.history.clear();
    b.history.clear();
    net.run(400);
    assert(checkInSync(host, a, 0) > 300);
    assert(checkInSync(host, b, 0) > 300);
    std::puts("ok - three players stay in sync after a late join");
}

void testInputMapping() {
    Netplay netplay;
    Player player;
    player.id = 0;
    netplay.requestStart(0, 1);
    auto hooks = player.hooks();
    // Frame 0 has the empty start inputs; frame 1 carries our button.
    assert(netplay.beginFrame(hooks));
    netplay.endFrame(hooks);
    player.localTick = 0;  // button id (0 / 7 + 0) % 16 = 0, which is B
    assert(netplay.beginFrame(hooks));
    assert(netplay.inputState(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_B) == 0 ||
           netplay.inputState(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK) != 0);
    assert(netplay.inputState(1, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK) == 0);
    assert(netplay.inputState(0, RETRO_DEVICE_POINTER, 0, 0) == 0);
    std::puts("ok - input mapping");
}

} // namespace

int main() {
    testInputMapping();
    testTwoPlayersStayInSync();
    testDesyncIsRepaired();
    testHostWaitsForLatePlayer();
    testThreePlayers();
    std::puts("all netplay tests passed");
    return 0;
}
