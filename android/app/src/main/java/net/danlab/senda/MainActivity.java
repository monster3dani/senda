package net.danlab.senda;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SendaPhotosPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
