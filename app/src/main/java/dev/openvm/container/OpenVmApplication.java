package dev.openvm.container;

import android.app.Application;

public class OpenVmApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        ContainerManager.get(this).init();
    }
}
