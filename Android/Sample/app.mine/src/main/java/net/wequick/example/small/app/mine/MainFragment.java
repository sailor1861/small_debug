package net.wequick.example.small.app.mine;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Keep;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.example.hellojni.HelloPluginJni;
import com.example.mylib.Greet;

import net.wequick.example.small.lib.utils.UIUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Created by galen on 15/11/12.
 */
@Keep
public class MainFragment extends Fragment {

    private static final int REQUEST_CODE_COLOR = 1000;
    private static final int REQUEST_CODE_CONTACTS = 1001;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_main, container, false);
        TextView tvSection = (TextView) rootView.findViewById(R.id.section_label);
        tvSection.setText(R.string.hello);
        tvSection.setTextColor(getResources().getColor(R.color.my_test_color2));

        Button button = (Button) rootView.findViewById(R.id.inter_start_button);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainFragment.this.getContext(), PickerActivity.class);
                startActivityForResult(intent, REQUEST_CODE_COLOR);
            }
        });

        button = (Button) rootView.findViewById(R.id.call_system_button);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_PICK,
                        android.provider.ContactsContract.Contacts.CONTENT_URI);
                startActivityForResult(intent, REQUEST_CODE_CONTACTS);
            }
        });

        try {
            InputStream is = getResources().getAssets().open("greet.txt");
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            String greet = br.readLine();
            is.close();

            TextView tvAssets = (TextView) rootView.findViewById(R.id.assets_label);
            tvAssets.setText("assets/greet.txt: " + greet);

            is = getResources().openRawResource(R.raw.greet);
            br = new BufferedReader(new InputStreamReader(is));
            greet = br.readLine();
            is.close();

            TextView tvRaw = (TextView) rootView.findViewById(R.id.raw_label);
            tvRaw.setText("res/raw/greet.txt: " + greet);

            is = getResources().openRawResource(R.raw.mq_new_message);
            System.out.println("### " + is.available());
            is.close();


        } catch (IOException e) {
            e.printStackTrace();
        }

        // TODO: Following will crash, try to fix it
//        getResources().openRawResourceFd(R.raw.greet);

        TextView tvLib = (TextView) rootView.findViewById(R.id.lib_label);
        tvLib.setText(Greet.hello());

        TextView tvJni = (TextView) rootView.findViewById(R.id.jni_label);
        tvJni.setText(HelloPluginJni.stringFromJNI());

        return rootView;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK) return;

        switch (requestCode) {
            case REQUEST_CODE_COLOR:
                Button button = (Button) getView().findViewById(R.id.inter_start_button);
                button.setText(getText(R.string.inter_start) + ": " + data.getStringExtra("color"));
                break;
            case REQUEST_CODE_CONTACTS:
                UIUtils.showToast(getContext(), "contact: " + data);
                testLibPlugin();
                break;
        }
    }

    /**
     * 测试业务插件，访问公共插件的资源; 看看资源ID是否一致！
     */
    public static void testLibPlugin() {
        // Java代码直接访问插件的资源
        Log.i("app.mine", "testLibPlugin, resId: " + Integer.toHexString(R.bool.my_test_bool) + " " +
                Integer.toHexString(R.color.my_test_color1) + " " +
                Integer.toHexString(R.color.my_test_color2) + " " +
                Integer.toHexString(R.mipmap.add));
//        Log.i("", "testLibPlugin, resId: " + net.wequick.example.small.lib.utils.R.bool.my_test_bool + " " +
//                net.wequick.example.small.lib.utils.R.color.my_test_color2 + " " +
//                net.wequick.example.small.lib.utils.R.array.my_test_colors + " " +
//                net.wequick.example.small.lib.utils.R.mipmap.add);

        Log.i("", "MyTextView  android.R " + Integer.toHexString(android.R.style.Theme_Holo_Light_NoActionBar) +
                " android.support.v7.appcompat.R" + Integer.toHexString(android.support.v7.appcompat.R.style.Theme_AppCompat_Light_NoActionBar));
    }


}
