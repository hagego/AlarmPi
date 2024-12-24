// definition of MQTT related constants

#include "mqtt.h"

// mosquitto 1.3.4 speaks MQTT Version 3.1
// change to MQTT_VERSION MQTT_VERSION_3_1_1 after upgrade to 1.3.5 or higher
#define MQTT_VERSION MQTT_VERSION_3_1

// MQTT client name
const char* mqttClientName = "alarmpidisplay";

// topics to publish
const char* mqttTopicPublishConnected          = MQTT_PREFIX "connected";                   // published after connect
const char* mqttTopicPublishAlive              = MQTT_PREFIX "alive";                       // published periodically as alive signal
const char* mqttTopicPublishButtonLightOn      = MQTT_PREFIX "buttonLightOn";               // published when light on button is pushed
const char* mqttTopicPublishButtonLightOff     = MQTT_PREFIX "buttonLightOff";              // published when light on button is pushed
const char* mqttTopicPublishButtonSkipAlarm    = MQTT_PREFIX "buttonSkipAlarm";             // published when skip alarm buttin is pushed

// topics to subscribe
const char* mqttTopicSubscribeSecondsSinceMidnight  = MQTT_PREFIX "secondsSinceMidnight";   // sets the local time (integer, seconds since midnight)
const char* mqttTopicSubscribeTemperature           = MQTT_PREFIX "temperature";            // sets the temperature (integer, degree celcious)
const char* mqttTopicSubscribeBacklight             = MQTT_PREFIX "backlight";              // sets the backlight brightness in percent
const char* mqttTopicSubscribeLight                 = MQTT_PREFIX "light";                  // sets the status of the light (on/off)
const char* mqttTopicSubscribeNextAlarm             = MQTT_PREFIX "nextAlarm";              // sets the next alarm display (integer, seconds since midnight)
const char* mqttTopicSubscribeWasteCollection       = MQTT_PREFIX "wasteCollection";        // sets the waste collection display (string)

