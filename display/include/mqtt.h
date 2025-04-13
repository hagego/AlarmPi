#ifndef __mqtt_h__
#define __mqtt_h__

// definition of MQTT related constants

// MQTT client name
extern const char* mqttClientName;

// MQTT topics
#define MQTT_PREFIX "alarmpi/testdisplay/"

// topics to publish
extern const char* mqttTopicPublishConnected;                   // published after connect
extern const char* mqttTopicPublishAlive;                       // published periodically as alive signal
extern const char* mqttTopicPublishButtonLightOn;               // published when light on button is pushed
extern const char* mqttTopicPublishButtonLightOff;              // published when light on button is pushed
extern const char* mqttTopicPublishButtonRadioOn;               // published when radio on button is pushed
extern const char* mqttTopicPublishButtonRadioOff;              // published when radio off button is pushed
extern const char* mqttTopicPublishButtonSkipAlarm;             // published when skip alarm buttin is pushed
extern const char* mqttTopicPublishSetAlarm;                    // published when a new alarm time must be set

// topics to subscribe
extern const char* mqttTopicSubscribeSecondsSinceMidnight;      // sets the local time (integer, seconds since midnight)
extern const char* mqttTopicSubscribeTemperature;               // sets the temperature (integer, degree celcious)
extern const char* mqttTopicSubscribeBacklight;                 // sets the backlight brightness in percent
extern const char* mqttTopicSubscribeNextAlarm;                 // sets the next alarm display (integer, seconds since midnight)
extern const char* mqttTopicSubscribeWasteCollection;           // sets the waste collection display (string)

#endif // __mqtt_h__