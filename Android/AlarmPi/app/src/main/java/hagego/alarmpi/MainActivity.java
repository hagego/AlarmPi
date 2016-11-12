package hagego.alarmpi;

import android.app.Activity;
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
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ExpandableListView;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.graphics.Color;
import android.widget.Toast;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import static java.lang.Math.abs;

public class MainActivity extends AppCompatActivity
        implements AdapterView.OnItemSelectedListener,View.OnClickListener, SeekBar.OnSeekBarChangeListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(Constants.LOG, "AlarmPi MainActivity onCreate()");
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        Alarm.setApplicationContext(this);

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
        spinnerSoundList        = (Spinner)findViewById(R.id.spinnerSoundList);
        listViewAlarms          = (ExpandableListView)findViewById(R.id.listViewAlarms);

        proxy = Proxy.getProxy(this,handler);
        AlarmListAdapter alarmListAdapter = new AlarmListAdapter(this,proxy);
        listViewAlarms.setAdapter(alarmListAdapter);

        final Activity activity=this;

        // setup a handler to check for completion of synchronization with AlarmPi
        // handler gets triggered out of onResume and then every 200ms until query finishes
        handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                Log.d(Constants.LOG, "message handler called with what="+msg.what);
                if(msg.what==Constants.MESSAGE_PROXY_SYNCHRONIZED) {
                    Log.d(Constants.LOG, "checking proxy status");
                    if (proxyStatusSynchronized.isDone()) {
                        // proxy synchronization is done
                        try {
                            if (proxyStatusSynchronized.get()) {
                                Log.d(Constants.LOG, "synchronization complete");
                                Toast.makeText(getApplicationContext(), R.string.stringConnectionSuccess, Toast.LENGTH_SHORT).show();

                                // display status
                                SharedPreferences prefs = getSharedPreferences(Constants.PREFS_KEY, MODE_PRIVATE);
                                int active = prefs.getInt("active", -1);
                                if (active == -1) {
                                    Log.e(Constants.LOG, "No active AlarmPi set in SharedPreferences");
                                    // error during synchronization
                                    Toast.makeText(getApplicationContext(), R.string.stringConnectionError, Toast.LENGTH_SHORT).show();
                                    return;
                                }

                                // connection successful, update GUI with proxy data
                                textViewConnectionState.setTextColor(Color.GREEN);
                                textViewConnectionState.setText(R.string.labelConnected);
                                textViewAlarmPiName.setText(prefs.getString("name" + active, ""));
                                textViewAlarmPiName.setTextColor(Color.GREEN);

                                if(proxy.getLightCount()>=1) {
                                    radioButtonLight1Off.setEnabled(true);
                                    radioButtonLight1On.setEnabled(true);

                                    if(proxy.getBrightness(0)>0) {
                                        // light 1 is on
                                        radioButtonLight1Off.setChecked(false);
                                        radioButtonLight1On.setChecked(true);
                                        seekBarLight1.setEnabled(true);
                                        seekBarLight1.setProgress(proxy.getBrightness(0));
                                    }
                                    else {
                                        // light 1 is off
                                        radioButtonLight1Off.setChecked(true);
                                        radioButtonLight1On.setChecked(false);
                                        seekBarLight1.setEnabled(false);
                                        seekBarLight1.setProgress(0);
                                    }
                                }

                                if(proxy.getLightCount()>=2) {
                                    radioButtonLight2Off.setEnabled(true);
                                    radioButtonLight2On.setEnabled(true);

                                    if(proxy.getBrightness(1)>0) {
                                        // light 2 is on
                                        radioButtonLight2Off.setChecked(false);
                                        radioButtonLight2On.setChecked(true);
                                        seekBarLight2.setEnabled(true);
                                        seekBarLight2.setProgress(proxy.getBrightness(1));
                                    }
                                    else {
                                        // light 2 is off
                                        radioButtonLight2Off.setChecked(true);
                                        radioButtonLight2On.setChecked(false);
                                        seekBarLight2.setEnabled(false);
                                        seekBarLight2.setProgress(0);
                                    }
                                }

                                radioButtonSoundOn.setEnabled(true);
                                radioButtonSoundOff.setEnabled(true);

                                ArrayAdapter<String> soundAdapter = new ArrayAdapter<String>(activity,
                                        android.R.layout.simple_spinner_item, proxy.getSoundList());
                                soundAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                                spinnerSoundList.setAdapter(soundAdapter);

                                Integer soundIndex = proxy.getActiveSound();
                                if(soundIndex!=null) {
                                    spinnerSoundList.setSelection(proxy.getActiveSound(), false);
                                }

                                if (proxy.getActiveVolume() > 0) {
                                    radioButtonSoundOn.setChecked(true);
                                    radioButtonSoundOff.setChecked(false);
                                    seekBarSound.setEnabled(true);
                                    seekBarSound.setProgress(proxy.getActiveVolume());
                                    //spinnerSoundList.setSelection(proxy.getActiveSound());
                                    spinnerSoundList.setEnabled(true);
                                } else {
                                    radioButtonSoundOn.setChecked(false);
                                    radioButtonSoundOff.setChecked(true);
                                    seekBarSound.setProgress(0);
                                    seekBarSound.setEnabled(false);
                                    spinnerSoundList.setEnabled(false);
                                }

                                handler.sendEmptyMessageDelayed(Constants.MESSAGE_ENABLE_LISTENERS,250);
                            } else {
                                // error during synchronization
                                Toast.makeText(getApplicationContext(), R.string.stringConnectionError, Toast.LENGTH_SHORT).show();
                            }
                        } catch (InterruptedException e) {
                            Log.e(Constants.LOG, e.getMessage());
                            Toast.makeText(getApplicationContext(), R.string.stringErrorConnect, Toast.LENGTH_SHORT).show();
                        } catch (ExecutionException e) {
                            Log.e(Constants.LOG, e.getMessage());
                            Toast.makeText(getApplicationContext(), R.string.stringErrorConnect, Toast.LENGTH_SHORT).show();
                        }
                    }
                }

                if(msg.what==Constants.MESSAGE_ENABLE_LISTENERS) {
                    // enable code in GUI widget listeners
                    Log.d(Constants.LOG, "enabling GUI widget listeners");
                    listenersEnabled = true;
                }
            }
        };

        // set widget callbacks
        radioButtonSoundOn.setOnClickListener(this);
        radioButtonSoundOff.setOnClickListener(this);
        spinnerSoundList.setOnItemSelectedListener(this);
        seekBarSound.setOnSeekBarChangeListener(this);

        radioButtonLight1On.setOnClickListener(this);
        radioButtonLight1Off.setOnClickListener(this);
        seekBarLight1.setOnSeekBarChangeListener(this);
        radioButtonLight2On.setOnClickListener(this);
        radioButtonLight2Off.setOnClickListener(this);
        seekBarLight2.setOnSeekBarChangeListener(this);
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
        spinnerSoundList.setEnabled(false);

        // connect to AlarmPi and synchronize data
        // Proxy will serialize the 2 methods in a different thread
        proxy.connect();
        proxyStatusSynchronized = proxy.synchronize();
        listenersEnabled = false;
    }

    @Override
    public void onPause() {

        Log.d(Constants.LOG,"AlarmPiFragment onPause()");
        proxy = Proxy.getProxy(this,handler);
        for( Alarm alarm:proxy.getAlarmList()) {
            if(alarm.getHasChanged()) {
                Log.d(Constants.LOG, "communicating alarm changes on ID "+alarm.getId()+" to AlarmPi");
                proxyStatusAlarmUpdated = proxy.updateAlarm(alarm);
            }
        }

        super.onPause();

        // disconnect from AlarmPi
        proxy.disconnect();
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
    // widget callbacks
    //
    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if(listenersEnabled) {
            Log.d(Constants.LOG, "sound selected: ID=" + position);
            proxy.playSound(position);
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {

    }

    @Override
    public void onClick(View v) {
        if(listenersEnabled) {
            if (v == radioButtonSoundOn) {
                Log.d(Constants.LOG, "sound on button clicked");
                proxy.updateVolume(25);
                proxy.playSound(spinnerSoundList.getSelectedItemPosition());
                seekBarSound.setEnabled(true);
                seekBarSound.setProgress(25);
                spinnerSoundList.setEnabled(true);
            }

            if (v == radioButtonSoundOff) {
                Log.d(Constants.LOG, "sound off button clicked");
                proxy.updateVolume(0);
                seekBarSound.setEnabled(false);
                seekBarSound.setProgress(0);
                spinnerSoundList.setEnabled(false);
            }

            if( v==radioButtonLight1On ) {
                Log.d(Constants.LOG, "light 1 on button clicked");
                proxy.updateBrightness(0,Constants.DEFAULT_BRIGHTNESS);
                seekBarLight1.setProgress(Constants.DEFAULT_BRIGHTNESS);
                seekBarLight1.setEnabled(true);
            }

            if( v==radioButtonLight1Off ) {
                Log.d(Constants.LOG, "light 1 off button clicked");
                proxy.updateBrightness(0,0);
                seekBarLight1.setProgress(0);
                seekBarLight1.setEnabled(false);
            }

            if( v==radioButtonLight2On ) {
                Log.d(Constants.LOG, "light 2 on button clicked");
                proxy.updateBrightness(1,Constants.DEFAULT_BRIGHTNESS);
                seekBarLight2.setProgress(Constants.DEFAULT_BRIGHTNESS);
                seekBarLight2.setEnabled(true);
            }

            if( v==radioButtonLight2Off ) {
                Log.d(Constants.LOG, "light 2 off button clicked");
                proxy.updateBrightness(1,0);
                seekBarLight2.setProgress(0);
                seekBarLight2.setEnabled(false);
            }
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if(listenersEnabled) {
            if (seekBar == seekBarLight1) {
                Log.d(Constants.LOG, "seekBar light 1 moved to " + progress);
                if(abs(progress-proxy.getBrightness(0))>10) {
                    proxy.updateBrightness(0, progress);
                }
            }

            if (seekBar == seekBarLight2) {
                Log.d(Constants.LOG, "seekBar light 2 moved to " + progress);
                if(abs(progress-proxy.getBrightness(1))>10) {
                    proxy.updateBrightness(1, progress);
                }
            }

            if (seekBar == seekBarSound) {
                Log.d(Constants.LOG, "seekBar sound moved to " + progress);
                if(abs(progress-proxy.getActiveVolume())>10) {
                    proxy.updateVolume(progress);
                }
            }
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        if(listenersEnabled) {
            if (seekBar == seekBarLight1) {
                Log.d(Constants.LOG, "seekBar light 1 tracking stop");
                proxy.updateBrightness(0,seekBarLight1.getProgress());
            }

            if (seekBar == seekBarLight2) {
                Log.d(Constants.LOG, "seekBar light 2 tracking stop");
                proxy.updateBrightness(1,seekBarLight2.getProgress());
            }

            if (seekBar == seekBarSound) {
                Log.d(Constants.LOG, "seekBar sound tracking stop");
                proxy.updateVolume(seekBarSound.getProgress());
            }
        }
    }

    //
    // private members
    //

    // GUID widgets
    private TextView           textViewConnectionState;    // label connection state
    private TextView           textViewAlarmPiName;        // label AlarmPi name
    private RadioButton        radioButtonLight1On;        // radio button light1 on
    private RadioButton        radioButtonLight1Off;       // radio button light1 off
    private SeekBar            seekBarLight1;              // dimmer light 2
    private RadioButton        radioButtonLight2On;        // radio button light2 on
    private RadioButton        radioButtonLight2Off;       // radio button light2 off
    private SeekBar            seekBarLight2;              // dimmer light 2
    private RadioButton        radioButtonSoundOn;         // radio button light2 on
    private RadioButton        radioButtonSoundOff;        // radio button light2 off
    private SeekBar            seekBarSound;               // dimmer light 2
    private Spinner            spinnerSoundList;           // sound list
    private ExpandableListView listViewAlarms;             // alarm list

    ArrayAdapter<String> soundListAdapter;                 // data adapter for sound list spinner
    boolean              listenersEnabled;                 // enables/disables GUI listeners

    // other data
    private Proxy       proxy;                            // proxy object for communication with AlarmPi

    // handlers
    private Handler              handler;                 // does GUI updates based on proxy status
    private Future<Boolean>      proxyStatusSynchronized;       // Future for synchronization status of AlarmPi
    private Future<Boolean>      proxyStatusAlarmUpdated;       // Future for synchronization status of updated alarm


}
