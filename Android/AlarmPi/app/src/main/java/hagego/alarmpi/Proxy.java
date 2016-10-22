package hagego.alarmpi;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;

/**
 * this class acts as a proxy to the AlarmPi server
 * implemented as Singleton
 *
 */
class Proxy {

    /**
     * private constructor - this class implements the singleton pattern
     */
    private Proxy() { }

    /**
     * returns the singleton object
     * @return singleton object
     */
    static Proxy getProxy() {
        return object;
    }

    /**
     * Connects to AlarmPi server
     * @return Future Boolean indicating success of the connect operation
     */
    Future<Boolean> connect() {
        return threadExecutor.submit(new Connect());
    }

    /**
     * disconnects from AlarmPi
     */
    void disconnect() {
        threadExecutor.execute(new Disconnect());
    }

    /**
     * Updates proxy with data read from AlarmPi
     * @return Future Boolean indicating the success of the synchronization
     */
    Future<Boolean> synchronize() {
        return threadExecutor.submit(new Synchronize());
    }

    /**
     * returns the list of alarms
     * @return alarm list
     */
    List<Alarm> getAlarmList() {
        return alarmList;
    }

    /**
     * returns the list of available sounds
     * @return list of available sounds
     */
    ArrayList<String> getSoundList() {
        return soundList;
    }

    /**
     * returns the LED brightness in %
     * @return LED brightness in percent (0 means LED is off)
     */
    int getLedBrightness() {
        return ledBrightness;
    }

    /**
     * returns the actual Volume in percent (0 is off)
     * @return actual Volume
     */
    int getActiveVolume() {
        return activeVolume;
    }

    /**
     * returns the index of the actual playing Sound (or null if sound is off)
     * @return actual playing Sound (or null if sound is off)
     */
    Integer getActiveSound() {
        return activeSound;
    }

    /**
     * returns the active 'sound off' timer setting
     * @return the active 'sound off' timer setting in seconds from last query
     */
    Integer getActiveTimer() {
        return activeTimer;
    }

    /**
     * Updates an alarm on AlarmPi
     * @param alarm alarm to update
     * @return Future of Boolean with the success of the update
     */
    Future<Boolean> updateAlarm(Alarm alarm) {
        return threadExecutor.submit(new UpdateAlarm(alarm));
    }

    /**
     * Updates the LED brightness on AlarmPi
     * @param ledBrightness in percent (0 means off)
     * @return Future of Boolean with the success of the update
     */
    Future<Boolean> updateLedBrightness(int ledBrightness) {
        String cmd = ledBrightness==0 ? "light off" : String.format("light %d", ledBrightness);
        return threadExecutor.submit(new UpdateCommand(cmd));
    }

    /**
     * Updates the sound volume on AlarmPi
     * @param volume in percent (0 means off)
     * @return Future of Boolean with the success of the update
     */
    Future<Boolean> updateVolume(int volume) {
        String cmd = volume==0 ? "sound off" : String.format("sound volume %d", volume);
        return threadExecutor.submit(new UpdateCommand(cmd));
    }

    /**
     * plays a sound on AlarmPi
     * @param soundId Index of sound in sound list
     * @return Future of Boolean with the success of the operation
     */
    Future<Boolean> playSound(int soundId) {
        activeSound = soundId;
        return threadExecutor.submit(new UpdateCommand("sound play "+soundId));
    }

    /**
     * Updates the sound timer
     * @param secondsFromNow seconds from now on until sound shall be switched off
     * @return Future of Boolean with the success of the operation
     */
    Future<Boolean> updateTimer(int secondsFromNow) {
        String cmd = secondsFromNow==0 ? "sound timer off" : String.format("sound timer %d", secondsFromNow);
        return threadExecutor.submit(new UpdateCommand(cmd));
    }

    //
    // private data
    //

    /**
     * nested class implementing Callable for the connect method
     */
    private class Connect implements Callable<Boolean> {
        @Override
        public Boolean call() {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mainActivity.getApplicationContext());
            String name = prefs.getString(mainActivity.getResources().getString(R.string.pref_key_alarmpi_name),"");
            String addr = prefs.getString(mainActivity.getResources().getString(R.string.pref_key_alarmpi_ipaddr),"");
            Log.d(Constants.LOG, "trying to connect to AlarmPi "+name+" at address "+addr);

            try {
                socket = new Socket(addr, 3947); // bedroom
                alarmPiOut = new BufferedOutputStream(socket.getOutputStream());
                alarmPiIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                // synchronize prompt
                readData();
                Log.d(Constants.LOG, "connected to AlarmPi "+name+" at address "+addr);

                return true;
            } catch (IOException e) {
                Log.e(Constants.LOG,"Unable to connect to AlarmPi "+name+" at address "+addr,e);
                socket = null;
            }

            return false;
        }
    }

    /**
     * nested class implementing Callable for the disconnect method
     */
    private class Disconnect implements Runnable {
        @Override
        public void run() {
            if(socket!=null) {
                try {
                    alarmPiOut.write("exit\n".getBytes());
                    alarmPiOut.flush();

                    socket.close();
                    socket = null;
                    alarmPiOut = null;
                    alarmPiIn = null;
                    Log.d(Constants.LOG, "disconnected from AlarmPi");
                } catch (IOException e) {
                    Log.e(Constants.LOG, "Unable to disconnect from AlarmPi", e);
                    socket = null;
                }
            }
        }
    }

    /**
     * nested class implementing Callable for the synchronize method
     */
    private class Synchronize implements Callable<Boolean> {
        @Override
        public Boolean call() {
            if(socket!=null) {
                try {
                    // get alarms
                    alarmPiOut.write("alarm?".getBytes());
                    alarmPiOut.flush();

                    String status = alarmPiIn.readLine();
                    if(!status.endsWith("OK")) {
                        Log.e(Constants.LOG, "Unable to synchronize alarm data from AlarmPi: "+readData());
                        return false;
                    }

                    String alarmData = readData();

                    // clear alarm list
                    alarmList.clear();

                    // process answer
                    for (String line : alarmData.split("\n")) {
                        String items[] = line.trim().split(" ");
                        if (items.length == 6) {
                            // this is a line with alarm data
                            Alarm alarm = new Alarm(Integer.parseInt(items[0]), Boolean.parseBoolean(items[1]),
                                    items[2], items[3], Integer.parseInt(items[4]),Boolean.parseBoolean(items[5]));
                            Log.d(Constants.LOG, "synchronized alarm: id=" + alarm.getId() + " " + alarm.toString());
                            alarmList.add(alarm);
                        }
                    }

                    // get LED setting
                    alarmPiOut.write("light?".getBytes());
                    alarmPiOut.flush();

                    status = alarmPiIn.readLine();
                    if(!status.endsWith("OK")) {
                        Log.e(Constants.LOG, "Unable to synchronize LED data from AlarmPi: "+readData());
                        return false;
                    }
                    String ledData = readData();
                    try {
                        ledBrightness = Integer.parseInt(ledData.substring(0, ledData.indexOf('\n')));
                        Log.d(Constants.LOG, "synchronized LED brightness: " + ledBrightness);
                    } catch (NumberFormatException e) {
                        Log.e(Constants.LOG, "Exception while parsing LED brightness value: ", e);
                        ledBrightness = 0;
                        return false;
                    }

                    // get active sound and sound list
                    alarmPiOut.write("sound?".getBytes());
                    alarmPiOut.flush();

                    status = alarmPiIn.readLine();
                    if(!status.endsWith("OK")) {
                        Log.e(Constants.LOG, "Unable to synchronize activeSound data from AlarmPi: " + readData());
                        return false;
                    }
                    // read active settings
                    String activeSettings = alarmPiIn.readLine();
                    String items[] = activeSettings.trim().split(" ");
                    activeSound    = Integer.parseInt(items[0]);
                    activeVolume   = Integer.parseInt(items[1]);
                    activeTimer    = Integer.parseInt(items[2]);

                    if(activeSound<0) {
                        activeSound  = null;
                        activeVolume = 0;
                    }

                    // clear and process activeSound list
                    String soundData = readData();
                    soundList.clear();
                    for(String line: soundData.split("\n")) {
                        items = line.trim().split(" ");
                        if(items.length==2) {
                            // this is a line with activeSound data
                            Log.d(Constants.LOG, "synchronized sound: "+items[0]);
                            soundList.add(items[0]);
                        }
                    }

                    Log.d(Constants.LOG, "synchronized active sound settings: index="+activeSound+" vol="+activeVolume+" timer="+activeTimer);
                } catch (IOException e) {
                    Log.e(Constants.LOG, "Unable to synchronize data from AlarmPi", e);
                    return false;
                }
            }
            else {
                // not connected
                Log.e(Constants.LOG, "Not connected to AlarmPi - Unable to synchronize data");
                return false;
            }

            return true;
        }
    }

    /**
     * nested class implementing Callable for the updateAlarm method
     * sends updated alarm settings to AlarmPi server
     */
    private class UpdateAlarm implements Callable<Boolean> {
        /**
         * Constructor
         * @param alarm alarm to update
         */
        UpdateAlarm(Alarm alarm) {
            this.alarm = alarm;
        }

        @Override
        public Boolean call() {
            if(socket!=null) {
                try {
                    // update alarm data
                    String cmd = String.format("alarm modify %d %s %s %d %b %b",alarm.getId(),alarm.getWeekDays(),alarm.getTime(),alarm.getSound(),alarm.getEnabled(),alarm.getOneTimeOnly());
                    Log.d(Constants.LOG, "Sending update cmd: "+cmd);
                    alarmPiOut.write(cmd.getBytes());
                    alarmPiOut.flush();

                    String status = alarmPiIn.readLine();
                    if(status.endsWith("OK")) {
                        readData();
                        alarm.resetHasChanged();
                        return true;
                    }
                    else {
                        String error = readData();
                        Log.e(Constants.LOG, "Unable to update alarm to AlarmPi: "+error);
                    }
                } catch (IOException e) {
                    Log.e(Constants.LOG, "Unable to update alarm to AlarmPi", e);
                }
            }
            else {
                // not connected
                Log.e(Constants.LOG, "Not connected to AlarmPi - Unable to update data");
            }

            // an error occured
            return false;
        }

        // private members
        private Alarm alarm;             // alarm to update
    }

    /**
     * nested private class implementing the Callable Interface
     * Used by all update commands to to the communication in a background thread
     */
    private class UpdateCommand implements Callable<Boolean> {
        /**
         * Constructor
         *
         * @param cmd command to send
         */
        UpdateCommand(String cmd) {
            this.cmd = cmd;
        }

        @Override
        public Boolean call() {
            if (socket != null) {
                try {
                    Log.d(Constants.LOG, "Sending update cmd: " + cmd);
                    alarmPiOut.write(cmd.getBytes());
                    alarmPiOut.flush();

                    String status = alarmPiIn.readLine();
                    if (status.endsWith("OK")) {
                        readData();
                        return true;
                    } else {
                        String error = readData();
                        Log.e(Constants.LOG, "Failed to send command " + cmd + " : " + error);
                    }
                } catch (IOException e) {
                    Log.e(Constants.LOG, "Failed to send command " + cmd,e);
                }
            } else {
                // not connected
                Log.e(Constants.LOG, "Not connected to AlarmPi - Unable to send command " + cmd);
            }

            // an error occured
            return false;
        }

        // private members
        private String cmd;
    }

    /**
     * reads data from AlarmPi until no more data is available
     */
    private String readData() {
        String retVal;
        final int SIZE = 1000;
        int       pos  = 0;
        char[] buffer = new char[SIZE];

        try {
            int bytes;
            do {
                bytes = alarmPiIn.read(buffer, pos, SIZE);
            } while(bytes<=0);

            retVal = new String(buffer,0,bytes);
        } catch (IOException e) {
            Log.e(Constants.LOG,"Unable to synchronize from AlarmPi",e);
            retVal = "";
        }

        return retVal;
    }


    //
    // private members
    //
    private static Proxy          object = new Proxy();         // singleton object
    private ExecutorService       threadExecutor = Executors.newSingleThreadExecutor();


    private Socket                socket;                       // networking objects
    private BufferedOutputStream  alarmPiOut;
    private BufferedReader        alarmPiIn;

    // thread safe alarm list
    private CopyOnWriteArrayList<Alarm> alarmList   = new CopyOnWriteArrayList<Alarm>();

    // read-only list with possible sounds (radio stations or playlists)
    private ArrayList<String>           soundList   = new ArrayList<String>();

    private int     ledBrightness;               // active LED brightness (PWM) in percent
    private int     activeVolume;                // active activeVolume in percent (0 = off)
    private Integer activeSound;                 // active activeSound or null
    private Integer activeTimer;                 // active 'sound off' timer (in seconds) or null
}
