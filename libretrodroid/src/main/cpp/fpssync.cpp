/*
 *     Copyright (C) 2019  Filippo Scognamiglio
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

#include <cmath>
#include "fpssync.h"
#include "log.h"

namespace libretrodroid {

unsigned FPSSync::advanceFrames() {
    if (useVSync) {
        if (!screenFasterThanExpected()) return 1;
        // One game frame per screen refresh would now run the game too fast (the screen went
        // back to a higher refresh rate): from here on, pace the game by the clock instead.
        LOGI("Screen refreshes faster than %f Hz, pacing frames by time", screenRefreshRate);
        useVSync = false;
    }

    if (lastFrame == MIN_TIME) {
        start();
    }

    auto now = std::chrono::steady_clock::now();
    auto frames = std::max((now - lastFrame) / sampleInterval, (long long) 1);
    lastFrame = lastFrame + sampleInterval * frames;

    return frames;
}

FPSSync::FPSSync(double contentRefreshRate, double screenRefreshRate) {
    this->contentRefreshRate = contentRefreshRate;
    this->screenRefreshRate = screenRefreshRate;
    this->useVSync = std::abs(contentRefreshRate - screenRefreshRate) < FPS_TOLERANCE;
    this->sampleInterval = std::chrono::microseconds((long) ((1000000L / contentRefreshRate)));
    this->averageVSyncInterval = 1.0 / screenRefreshRate;
    reset();
}

bool FPSSync::screenFasterThanExpected() {
    auto now = std::chrono::steady_clock::now();
    if (lastVSync != MIN_TIME) {
        double interval = std::chrono::duration<double>(now - lastVSync).count();
        // Long gaps (pauses, loading) say nothing about the screen.
        if (interval < 0.1) {
            averageVSyncInterval += (interval - averageVSyncInterval) * 0.05;
        }
    }
    lastVSync = now;
    return averageVSyncInterval < 0.8 / contentRefreshRate;
}

void FPSSync::start() {
    LOGI("Starting game with fps %f on a screen with refresh rate %f. Using vsync: %d", contentRefreshRate, screenRefreshRate, useVSync);
    lastFrame = std::chrono::steady_clock::now();
}

void FPSSync::reset() {
    lastFrame = MIN_TIME;
    lastVSync = MIN_TIME;
}

double FPSSync::getTimeStretchFactor() {
    return useVSync ? contentRefreshRate / screenRefreshRate : 1.0;
}

void FPSSync::wait() {
    if (useVSync) return;
    std::this_thread::sleep_until(lastFrame);
}

} //namespace libretrodroid
