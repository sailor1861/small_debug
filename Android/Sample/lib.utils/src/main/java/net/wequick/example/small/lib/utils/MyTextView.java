package net.wequick.example.small.lib.utils;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.TextView;

/**
 * Created by leon on 2016/2/17.
 */
public class MyTextView extends TextView {
    public MyTextView(Context context) {
        super(context);
        init(context, null);
    }

    public MyTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public MyTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context,AttributeSet attrs) {
        if (attrs == null) {
            return;
        }

        TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.MyTextView);
        String label = ta.getString(R.styleable.MyTextView_label);
        setText("MyTextView: " + label);

        Log.i("", "MyTextView  lib.utils.res " + Integer.toHexString(R.bool.my_test_bool) + " " +
                Integer.toHexString(R.color.my_test_color1) + " " +
                Integer.toHexString(R.color.my_test_color2) + " " +
                Integer.toHexString(R.mipmap.add));

        Log.i("", "MyTextView  android.R " + Integer.toHexString(android.R.style.Theme_Holo_Light_NoActionBar) +
                " android.support.v7.appcompat.R" + Integer.toHexString(android.support.v7.appcompat.R.style.Theme_AppCompat_Light_NoActionBar));
    }
}
