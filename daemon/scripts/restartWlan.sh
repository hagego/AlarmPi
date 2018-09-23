#!/bin/bash
# check if a wlan0 if exists
LOGFILE=/var/log/alarmpi/restartWlan.log

if echo `/sbin/ifconfig` | grep -q wlan0; then
  # check if there is IP Address
  if echo `/sbin/ifconfig wlan0` | grep -q "inet"; then
    ping -c 1 192.168.178.1
    date >> $LOGFILE
    echo "wlan connection OK" >> $LOGFILE
    exit 0
  fi
  date >> $LOGFILE
  echo "wlan0 exists but no IP address" >> $LOGFILE
  sudo /sbin/modprobe -r 8192cu 2>&1 >> $LOGFILE
fi
sleep 1
date >> $LOGFILE
echo "creating adapter" >> $LOGFILE
sudo /sbin/modprobe 8192cu 2>&1 >> $LOGFILE

exit 0
