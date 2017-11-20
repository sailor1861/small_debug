package com.example.mysmall.lib.style;

import android.util.Log;

/**
 * Created by yedr on 2017/11/20.
 */

public class StyleTestJava {

    private static final String TAG = "StyleTestJava";

    public int add(int s1, int s2) {
        Log.i(TAG, String.format("add(): %d + %d = %d", s1, s2, s1+s2));
        return s1 + s2;
    }

    /**
     * lib插件访问自身的固定资源；打印资源ID； 对比App插件访问本lib插件的资源时，资源ID是否固定
     */
    private void testLibStyleRes() {
        // App插件访问lib插件的java类
        new StyleTestJava().add(1, 14);

        // App插件在代码中访问lib插件的R.java: 要求资源ID固定
        Log.i(TAG, "R.color.colorLight: " + Integer.toHexString(R.color.colorLight) + "; colorPrimaryDark: " + Integer.toHexString(R.color.colorPrimaryDark));
    }
}
