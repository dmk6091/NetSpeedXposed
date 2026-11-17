package android.view;

import android.graphics.Rect;

import java.util.concurrent.Executor;

/** Compile-time stub of the hidden framework class (real one is on the device). */
public abstract class CompositionSamplingListener {
    public CompositionSamplingListener(Executor executor) {
        throw new RuntimeException("stub");
    }

    public abstract void onSampleCollected(float medianLuma);

    public static void register(CompositionSamplingListener listener, int displayId,
                                SurfaceControl stopLayer, Rect samplingArea) {
        throw new RuntimeException("stub");
    }

    public static void unregister(CompositionSamplingListener listener) {
        throw new RuntimeException("stub");
    }
}
