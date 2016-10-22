package hagego.alarmpi;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.graphics.Color;

import java.util.concurrent.Future;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(Constants.LOG, "AlarmPi MainActivity onCreate()");
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // create widgets
        textViewConnectionState = (TextView) findViewById(R.id.textViewConnection);
        textViewAlarmPiName     = (TextView) findViewById(R.id.textViewAlarmPiName);
        radioButtonLight1On     = (RadioButton)findViewById(R.id.radioLight1On);
        radioButtonLight1Off    = (RadioButton)findViewById(R.id.radioLight1Off);
        seekBarLight1           = (SeekBar)findViewById(R.id.seekBarLight1);
        radioButtonLight2On     = (RadioButton)findViewById(R.id.radioLight2On);
        radioButtonLight2Off    = (RadioButton)findViewById(R.id.radioLight2Off);
        seekBarLight2           = (SeekBar)findViewById(R.id.seekBarLight2);
        radioButtonSoundOn      = (RadioButton)findViewById(R.id.radioSoundOn);
        radioButtonSoundOff     = (RadioButton)findViewById(R.id.radioSoundOff);
        seekBarSound            = (SeekBar)findViewById(R.id.seekBarSound);

        proxyStatusSynchronizeHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {

            }
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(Constants.LOG, "AlarmPi MainActivity onResume()");

        SharedPreferences prefs = getSharedPreferences(Constants.PREFS_KEY,MODE_PRIVATE);
        int active = prefs.getInt("active",-1);

        // set all widgets to disabled
        textViewConnectionState.setTextColor(Color.RED);
        textViewConnectionState.setText(R.string.labelDisconnected);
        textViewAlarmPiName.setText("");

        radioButtonLight1On.setEnabled(false);
        radioButtonLight1Off.setEnabled(false);
        seekBarLight1.setEnabled(false);
        radioButtonLight2On.setEnabled(false);
        radioButtonLight2Off.setEnabled(false);
        seekBarLight2.setEnabled(false);
        radioButtonSoundOn.setEnabled(false);
        radioButtonSoundOff.setEnabled(false);
        seekBarSound.setEnabled(false);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_select) {
            startActivity (new Intent(this, SelectActivity.class));
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    //
    // private members
    //

    // GUID widgets
    private TextView    textViewConnectionState;          // label connection state
    private TextView    textViewAlarmPiName;              // label AlarmPi name
    private RadioButton radioButtonLight1On;              // radio button light1 on
    private RadioButton radioButtonLight1Off;             // radio button light1 off
    private SeekBar     seekBarLight1;                    // dimmer light 2
    private RadioButton radioButtonLight2On;              // radio button light2 on
    private RadioButton radioButtonLight2Off;             // radio button light2 off
    private SeekBar     seekBarLight2;                    // dimmer light 2
    private RadioButton radioButtonSoundOn;               // radio button light2 on
    private RadioButton radioButtonSoundOff;              // radio button light2 off
    private SeekBar     seekBarSound;                     // dimmer light 2

    // handlers
    private Handler         proxyStatusSynchronizeHandler; // does GUI updates based on proxy status
    private Future<Boolean> proxyStatusSynchronized;       // Future for synchronized status of AlarmPi
}
