package com.kivo.sajilib;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

public class MainDetectActivity extends AppCompatActivity {

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_detect);

        //navigate to card
        Button _cardDetect = findViewById(R.id.card_detect_action);
                _cardDetect.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        //Intent intent = new Intent(getApplicationContext(), CardDetectActivity.class);
                        //        startActivity(intent);
                        openCardDetect();
                    }
                });

        Button _backDetect = findViewById(R.id.back_detect_action);
                _backDetect.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        //Intent intent = new Intent(getApplicationContext(), BackDetectActivity.class);
                        //startActivity(intent);
                        openBackDetect();
                    }
                });
    }

    public void openCardDetect() {
        Intent intent = new Intent(getApplicationContext(), CardDetectActivity.class);
        startActivity(intent);
    }

    public void openBackDetect() {
        Intent intent = new Intent(getApplicationContext(), BackDetectActivity.class);
        startActivity(intent);
    }
}