package hagego.alarmpi;

/**
 * app-wide constants
 */
interface Constants {
    String              LOG       = "hagego.alarmpi";   // logging tag
    String              PREFS_KEY = "hagego.alarmpi";   // shared preferences key
    static final int    PORT      = 3947;               // TCP port for communicattion to AlarmPi
    static final int    DEFAULT_BRIGHTNESS = 20;        // default light brightness

    // message types
    static final int    MESSAGE_PROXY_SYNCHRONIZED = 1;
    static final int    MESSAGE_ENABLE_LISTENERS   = 2;
}
