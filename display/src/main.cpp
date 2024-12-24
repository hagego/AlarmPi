#include <PubSubClient.h>

#include <WiFi.h>
#include <WiFiClient.h>

/* AlarmPi Display, based on the following sample code for CYD

    Rui Santos & Sara Santos - Random Nerd Tutorials
    THIS EXAMPLE WAS TESTED WITH THE FOLLOWING HARDWARE:
    1) ESP32-2432S028R 2.8 inch 240×320 also known as the Cheap Yellow Display (CYD): https://makeradvisor.com/tools/cyd-cheap-yellow-display-esp32-2432s028r/
      SET UP INSTRUCTIONS: https://RandomNerdTutorials.com/cyd/
    2) REGULAR ESP32 Dev Board + 2.8 inch 240x320 TFT Display: https://makeradvisor.com/tools/2-8-inch-ili9341-tft-240x320/ and https://makeradvisor.com/tools/esp32-dev-board-wi-fi-bluetooth/
      SET UP INSTRUCTIONS: https://RandomNerdTutorials.com/esp32-tft/
    Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files.
    The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
*/

// include WLAN authentification information/home/hagen/Maker/repos/SmartHome/RabbitHatchDoor/RabbitHatchDoor/WiFiInfo.h
#include "WifiInfo.h"

#ifndef WIFI_SSID
#define WIFI_SSID "my_ssid"
#define WIFI_PSK  "my_psk"
#endif

#include "ui.h"
#include "mqtt.h"


// speed of serial interface for debug messages
#define SERIAL_SPEED 115200

// MQTT broker IP address
const char* mqtt_server = "192.168.178.27";

WiFiClient espClient;
PubSubClient mqttClient(espClient);
UI ui(mqttClient);

// buffer for sprintfs
char buffer[512];

// time of day separated into hours, minutes, seconds
uint8_t time_h=0,
        time_m=0,
        time_s=0;

// MQTT callback function
void mqttCallback(char* topic, byte* payload, unsigned int length) {
  Serial.print("MQTT Message arrived [");
  Serial.print(topic);
  Serial.print("] value=");
  for (int i = 0; i < length; i++) {
    Serial.print((char)payload[i]);
  }
  Serial.println();

  char payloadString[length+1];
  strncpy(payloadString,(char*)payload,length);
  payloadString[length] = 0;
    
  if(strcmp(topic,mqttTopicSubscribeSecondsSinceMidnight)==0) {
    uint32_t secondsSinceMidnight = atol(payloadString);
    sprintf(buffer,"received seconds since midnight: %d",secondsSinceMidnight);
    Serial.println(buffer);
    if(secondsSinceMidnight>86400) {
      secondsSinceMidnight = 86400;
    }
    time_h = secondsSinceMidnight/3600;
    time_m = (secondsSinceMidnight-time_h*3600)/60;
    time_s = (secondsSinceMidnight-time_h*3600-time_m*60);
    ui.displayTime(time_h,time_m);
  }

  if(strcmp(topic,mqttTopicSubscribeNextAlarm)==0) {
    if(length==0) {
      ui.clearAlarmTime();
      return;
    }

    uint32_t secondsSinceMidnight = atol(payloadString);
    sprintf(buffer,"received next alarm: %d",secondsSinceMidnight);
    Serial.println(buffer);
    if(secondsSinceMidnight>86400) {
      secondsSinceMidnight = 86400;
    }
    ui.displayAlarmTime(secondsSinceMidnight/3600, (secondsSinceMidnight%3600)/60);

    return;
  }

  if(strcmp(topic,mqttTopicSubscribeTemperature)==0) {
    int8_t temperature = atoi(payloadString);
    sprintf(buffer,"received temperature: %d",temperature);
    Serial.println(buffer);
    ui.displayTemperature(temperature);

    return;
  }

  if(strcmp(topic,mqttTopicSubscribeBacklight)==0) {
    uint8_t brightness = atoi(payloadString);
    sprintf(buffer,"received backlight brightness: %d",brightness);
    Serial.println(buffer);
    ui.setBacklight(brightness);

    return;
  }

  if(strcmp(topic,mqttTopicSubscribeWasteCollection)==0) {
    if(length==0) {
      ui.clearWasteCollection();
      return;
    }
    
    sprintf(buffer,"received waste collection: %s",payloadString);
    Serial.println(buffer);
    ui.displayWasteCollection(payloadString);

    return;
  }
}


void setup() {
  // setup serial
  Serial.begin(SERIAL_SPEED);
  delay(100);
  Serial.println();
  Serial.println(F("AlarmPi Display started"));

    // Connect to WiFi network
  WiFi.mode(WIFI_STA);
  Serial.print(F("Connecting to SID "));
  Serial.println(WIFI_SSID);
  WiFi.begin(WIFI_SSID, WIFI_PSK);

  static int COUNTER_MAX = 100;
  int counter = 0;
  while (WiFi.status() != WL_CONNECTED && counter<COUNTER_MAX) {
    delay(100);
    Serial.print(".");
    counter++;
  }

  if(counter>=COUNTER_MAX) {
    Serial.print(F("Connection failed - restarting"));
    ESP.restart();
  }

  Serial.println("");
  Serial.print(F("Connected to WiFi, IP address="));
  Serial.println(WiFi.localIP());

  // connect to MQTT broker
  mqttClient.setServer(mqtt_server, 1883);
  mqttClient.setCallback(mqttCallback);
  
  sprintf(buffer,"Attempting MQTT connection to broker at %s as client %s-%d",mqtt_server,mqttClientName,ESP.getEfuseMac());
  Serial.println(buffer);
    
  // Attempt to connect
  sprintf(buffer,"%s-%d",mqttClientName,ESP.getEfuseMac());
  if (mqttClient.connect(buffer)) {
    Serial.println(F("MQTT connected"));
    // Once connected, publish an announcement...
    mqttClient.publish(mqttTopicPublishConnected, "connected");

    // subscribe topics
    mqttClient.subscribe(MQTT_PREFIX "#");
  }
  else {
    Serial.println(F("connection failed"));
  }

  ui.initTouchScreen();
}



uint8_t loopCounter = 0;



void loop() {
  // reconnect to WIFI if needed
  while (WiFi.status() != WL_CONNECTED){
    Serial.println("WIFI disconnected");
    WiFi.begin(WIFI_SSID, WIFI_PSK);
    uint8_t timeout = 8;
    while (timeout && (WiFi.status() != WL_CONNECTED)) {
      timeout--;
      delay(1000);
    }
    if(WiFi.status() == WL_CONNECTED) {
      Serial.println("WIFI reconnected");

      sprintf(buffer,"%s-%d",mqttClientName,ESP.getEfuseMac());
      if (mqttClient.connect(buffer)) {
        Serial.println(F("MQTT reconnected"));      
        mqttClient.publish(mqttTopicPublishConnected, "connected");
      }
    }
  }

  ui.handleTouchScreen();

  loopCounter++;
  if(loopCounter==10) {
    // 1 second passed
    loopCounter = 0;

    // check for MQTT messages and adjust time
    mqttClient.loop();

    // adjust time
    time_s++;
    if(time_s>=60) {
      time_s=0;
      time_m++;

      Serial.print("new minute started: ");
      Serial.println(time_m);

      if(time_m%10==0) {
        // send alive ping via MQTT every 10s
        Serial.println("sending alive ping");
        mqttClient.publish(mqttTopicPublishAlive, "alive");
      }

      if(time_m>=60) {
        time_m=0;
        time_h++;
        if(time_h>=24) {
          time_h=0;
        }
      }
    }
    ui.displayTime(time_h,time_m);
  }

  // sleep for 100ms
  delay(100);
}
