package android.app.prediction;

import android.os.UserHandle;

public final class AppTarget {
    public AppTargetId getId() { throw new RuntimeException("stub"); }
    public String getPackageName() { throw new RuntimeException("stub"); }
    public String getClassName() { throw new RuntimeException("stub"); }
    public UserHandle getUser() { throw new RuntimeException("stub"); }
    public int getRank() { throw new RuntimeException("stub"); }

    public static final class Builder {
        public Builder(AppTargetId id, String packageName, UserHandle user) { throw new RuntimeException("stub"); }
        public Builder setClassName(String className) { throw new RuntimeException("stub"); }
        public Builder setRank(int rank) { throw new RuntimeException("stub"); }
        public AppTarget build() { throw new RuntimeException("stub"); }
    }
}
