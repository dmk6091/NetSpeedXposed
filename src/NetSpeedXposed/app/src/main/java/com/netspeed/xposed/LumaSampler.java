package com.netspeed.xposed;

import java.util.concurrent.Executor;

/** Median brightness (0 dark .. 1 light) of the screen under the status bar, from SurfaceFlinger. */
final class LumaSampler extends android.view.CompositionSamplingListener {
    volatile float luma = -1f;

    LumaSampler(Executor e) {
        super(e);
    }

    @Override
    public void onSampleCollected(float medianLuma) {
        luma = medianLuma;
    }
}
