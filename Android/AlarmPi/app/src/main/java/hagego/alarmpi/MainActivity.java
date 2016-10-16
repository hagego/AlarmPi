package hagego.alarmpi;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.RadioButton;
import android.widget.SeekBar;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // create widgets
        radioButtonLigh1On  = (RadioButton)findViewById(R.id.radioLight1On);
        radioButtonLigh1Off = (RadioButton)findViewById(R.id.radioLight1Off);
        seekBarLight1       = (SeekBar)findViewById(R.id.seekBarLight1);
        radioButtonLigh2On  = (RadioButton)findViewById(R.id.radioLight2On);
        radioButtonLigh2Off = (RadioButton)findViewById(R.id.radioLight2Off);
        seekBarLight2       = (SeekBar)findViewById(R.id.seekBarLight2);
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d("alarmpi", "AlarmPi MainActivity onResume()");

        // set all widgets to disabled
        radioButtonLigh1On.setEnabled(false);
        radioButtonLigh1Off.setEnabled(false);
        seekBarLight1.setEnabled(false);
        radioButtonLigh2On.setEnabled(false);
        radioButtonLigh2Off.setEnabled(false);
        seekBarLight2.setEnabled(false);
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
    private RadioButton radioButtonLigh1On;              // radio button light1 on
    private RadioButton radioButtonLigh1Off;             // radio button light1 off
    private SeekBar     seekBarLight1;                   // dimmer light 2
    private RadioButton radioButtonLigh2On;              // radio button light2 on
    private RadioButton radioButtonLigh2Off;             // radio button light2 off
    private SeekBar     seekBarLight2;                   // dimmer light 2
}
