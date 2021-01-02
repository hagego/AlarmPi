package hagego.alarmpi;

import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/**
 * activity to add a new AlarmPi to the list of known ones
 */
public class AddActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(Constants.LOG, "AlarmPi AddActivity onCreate()");
        setContentView(R.layout.activity_add);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        Button buttonOk     = findViewById(R.id.buttonAddOk);
        Button buttonCancel = findViewById(R.id.buttonAddCancel);
        editTextName        = findViewById(R.id.editTextAddName);
        editTextHostname    = findViewById(R.id.editTextAddHostname);

        buttonOk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharedPreferences prefs = getSharedPreferences(Constants.PREFS_KEY,MODE_PRIVATE);
                int count = prefs.getInt("count",0);

                SharedPreferences.Editor editor = prefs.edit();

                editor.putString("name"+count,editTextName.getText().toString());
                editor.putString("hostname"+count,editTextHostname.getText().toString());
                count++;
                editor.putInt("count",count);

                editor.commit();

                finish();
            }
        });

        buttonCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });


        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    // private members
    private TextView editTextName;
    private TextView editTextHostname;
}
