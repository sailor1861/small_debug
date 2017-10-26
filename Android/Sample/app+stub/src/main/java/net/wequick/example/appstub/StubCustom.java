package net.wequick.example.appstub;

import android.util.Log;

/**
 * Created by galen on 15/11/3.
 */
public class StubCustom {

    public StubCustom() {
        Log.i("StubCustom", "new StubCustom Instance");
    }

    public void log(String message) {
        Log.i("StubCustom", "log: " + message);
    }
}
