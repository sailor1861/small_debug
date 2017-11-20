package com.example.mysmall.lib.style;

import android.util.Log;

/**
 * Created by yedr on 2017/11/20.
 */

public class StyleTestJava {

    public int add(int s1, int s2) {
        Log.i("StyleTestJava", String.format("add(): %d + %d = %d", s1, s2, s1+s2));
        return s1 + s2;
    }
}
