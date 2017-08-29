package net.wequick.example.small.appok_if_stub;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.MenuItem;

import net.wequick.example.small.lib.utils.MyTextView;

public class TransitionActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transition);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        overridePendingTransition(R.anim.slide_left_in, R.anim.slide_left_out);

        testRes();
        MyTextView.libUitlsTestRes();
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
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_right_in, R.anim.slide_right_out);
    }

    private void testRes() {
        // TestCase：lib插件访问系统资源，访问support包资源
        // TestCase: lib插件访问app+sub资源
        Log.e("yedr", "[app.ok] android.R " + Integer.toHexString(android.R.style.Theme_Holo_Light_NoActionBar) +
                "; android.support.v7.appcompat.R" + Integer.toHexString(android.support.v7.appcompat.R.style.Theme_AppCompat_Light_NoActionBar) +
                "; R.string.stub_new" + Integer.toHexString(R.string.stub_new));
    }
}
