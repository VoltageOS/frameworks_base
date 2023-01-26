/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.display.brightness.strategy;

import static org.junit.Assert.assertEquals;

import android.hardware.display.DisplayManagerInternal;
import android.view.Display;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.brightness.BrightnessReason;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class FollowerBrightnessStrategyTest {
    private FollowerBrightnessStrategy mFollowerBrightnessStrategy;

    @Before
    public void before() {
        mFollowerBrightnessStrategy = new FollowerBrightnessStrategy(Display.DEFAULT_DISPLAY);
    }

    @Test
    public void testUpdateBrightness() {
        DisplayManagerInternal.DisplayPowerRequest
                displayPowerRequest = new DisplayManagerInternal.DisplayPowerRequest();
        float brightnessToFollow = 0.2f;
        mFollowerBrightnessStrategy.setBrightnessToFollow(brightnessToFollow);
        BrightnessReason brightnessReason = new BrightnessReason();
        brightnessReason.setReason(BrightnessReason.REASON_FOLLOWER);
        DisplayBrightnessState expectedDisplayBrightnessState =
                new DisplayBrightnessState.Builder()
                        .setBrightness(brightnessToFollow)
                        .setBrightnessReason(brightnessReason)
                        .setSdrBrightness(brightnessToFollow)
                        .setDisplayBrightnessStrategyName(mFollowerBrightnessStrategy.getName())
                        .build();
        DisplayBrightnessState updatedDisplayBrightnessState =
                mFollowerBrightnessStrategy.updateBrightness(displayPowerRequest);
        assertEquals(expectedDisplayBrightnessState, updatedDisplayBrightnessState);
    }

}
