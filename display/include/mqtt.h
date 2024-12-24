#ifndef __mqtt_h__
#define __mqtt_h__

// definition of MQTT related constants

// mosquitto 1.3.4 speaks MQTT Version 3.1
// change to MQTT_VERSION MQTT_VERSION_3_1_1 after upgrade to 1.3.5 or higher
#define MQTT_VERSION MQTT_VERSION_3_1

// MQTT client name
extern const char* mqttClientName;

// MQTT topics
#define MQTT_PREFIX "alarmpi/display/"

// topics to publish
extern const char* mqttTopicPublishConnected;                   // published after connect
extern const char* mqttTopicPublishAlive;                       // published periodically as alive signal
extern const char* mqttTopicPublishButtonLightOn;               // published when light on button is pushed
extern const char* mqttTopicPublishButtonLightOff;              // published when light on button is pushed
extern const char* mqttTopicPublishButtonSkipAlarm;             // published when skip alarm buttin is pushed

// topics to subscribe
extern const char* mqttTopicSubscribeSecondsSinceMidnight;      // sets the local time (integer, seconds since midnight)
extern const char* mqttTopicSubscribeTemperature;               // sets the temperature (integer, degree celcious)
extern const char* mqttTopicSubscribeBacklight;                 // sets the backlight brightness in percent
extern const char* mqttTopicSubscribeNextAlarm;                 // sets the next alarm display (integer, seconds since midnight)
extern const char* mqttTopicSubscribeWasteCollection;           // sets the waste collection display (string)

#endif // __mqtt_h__