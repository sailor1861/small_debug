package net.wequick.example.small.app.detail;

import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.MenuItem;
import android.widget.TextView;

import com.example.mysmall.lib.style.StyleTestJava;

import net.wequick.small.Small;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "app.detail";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            System.out.println("savedInstanceState: " + savedInstanceState);
        }

        setContentView(R.layout.activity_main);
        setTitle("Detail");
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        Uri uri = Small.getUri(this);
        if (uri != null) {
            String from = uri.getQueryParameter("from");
            if (from != null) {
                TextView tvFrom = (TextView) findViewById(R.id.tvFrom);
                tvFrom.setText("-- Greet from " + from);
            }
        }

        testLibStylePlugin();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("Hello", "Small");
        super.onSaveInstanceState(outState);
    }

    /**
     * 验证App插件对lib插件的访问：包括Java代码，R.java资源ID
     */
    private void testLibStylePlugin() {
        // App插件访问lib插件的java类
        new StyleTestJava().add(1, 14);

        // App插件在代码中访问lib插件的R.java: 要求资源ID固定
        Log.i(TAG, "R.color.colorLight: " + Integer.toHexString(R.color.colorLight) + "; colorPrimaryDark: " + Integer.toHexString(R.color.colorPrimaryDark) +
                "; [lib.utils]R.color.my_test_color2: " + Integer.toHexString(R.color.my_test_color2));
    }
}
