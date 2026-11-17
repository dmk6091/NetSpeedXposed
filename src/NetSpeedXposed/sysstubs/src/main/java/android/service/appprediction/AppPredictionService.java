package android.service.appprediction;

import android.app.Service;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionSessionId;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.AppTargetId;
import android.content.Intent;
import android.os.CancellationSignal;
import android.os.IBinder;

import java.util.List;
import java.util.function.Consumer;

public abstract class AppPredictionService extends Service {
    public static final String SERVICE_INTERFACE = "android.service.appprediction.AppPredictionService";

    @Override
    public final IBinder onBind(Intent intent) { throw new RuntimeException("stub"); }

    public void onCreatePredictionSession(AppPredictionContext context, AppPredictionSessionId sessionId) { }
    public abstract void onAppTargetEvent(AppPredictionSessionId sessionId, AppTargetEvent event);
    public abstract void onLaunchLocationShown(AppPredictionSessionId sessionId, String launchLocation,
            List<AppTargetId> targetIds);
    public abstract void onSortAppTargets(AppPredictionSessionId sessionId, List<AppTarget> targets,
            CancellationSignal cancellationSignal, Consumer<List<AppTarget>> callback);
    public void onStartPredictionUpdates() { }
    public void onStopPredictionUpdates() { }
    public abstract void onRequestPredictionUpdate(AppPredictionSessionId sessionId);
    public abstract void onDestroyPredictionSession(AppPredictionSessionId sessionId);
    public final void updatePredictions(AppPredictionSessionId sessionId, List<AppTarget> targets) {
        throw new RuntimeException("stub");
    }
}
