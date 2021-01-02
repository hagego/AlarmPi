package alarmpi;

import java.lang.invoke.MethodHandles;
import java.util.logging.Logger;

public class LightControlMqtt extends LightControl {

	LightControlMqtt(int id, String name) {
		super(id, name);
		
		String section = "light"+id;

		topicsDefined = true;
		mqttTopicOn = Configuration.getConfiguration().getValue(section, "mqttTopicOn", null);
		if(mqttTopicOn==null) {
			log.severe("mqttTopicOn not defined for light id="+id+", name="+name);
			topicsDefined = false;
		}
		mqttValueOn = Configuration.getConfiguration().getValue(section, "mqttValueOn", null);
		if(mqttValueOn==null) {
			log.severe("mqttValueOn not defined for light id="+id+", name="+name);
			topicsDefined = false;
		}
		mqttTopicOff = Configuration.getConfiguration().getValue(section, "mqttTopicOff", null);
		if(mqttTopicOff==null) {
			log.severe("mqttTopicOff not defined for light id="+id+", name="+name);
			topicsDefined = false;
		}
		mqttValueOff = Configuration.getConfiguration().getValue(section, "mqttValueOff", null);
		if(mqttValueOff==null) {
			log.severe("mqttValueOff not defined for light id="+id+", name="+name);
			topicsDefined = false;
		}
		
		isOn = false;
	}

	@Override
	void setOff() {
		if(topicsDefined) {
			log.fine("switching MQTT light off: "+getName());
			MqttClient.getMqttClient().publish(mqttTopicOff, mqttValueOff);
		}
		else {
			log.warning("unable to switch off MQTT light - topics not defined");
		}
	}

	@Override
	void setBrightness(double percentage) {
		if(topicsDefined) {
			if(percentage>0) {
				log.fine("switching MQTT light on: "+getName());
				MqttClient.getMqttClient().publish(mqttTopicOn, mqttValueOn);
				isOn = true;
			}
			else {
				log.fine("switching MQTT light off: "+getName());
				MqttClient.getMqttClient().publish(mqttTopicOff, mqttValueOff);
				isOn = false;
			}
		}
		else {
			log.warning("unable to switch off MQTT light - topics not defined");
		}
	}

	@Override
	double getBrightness() {
		return isOn ? 100 : 0;
	}

	@Override
	void setPwm(int pwmValue) {
		setBrightness(pwmValue);
	}

	@Override
	int getPwm() {
		return isOn ? 100 : 0;
	}

	@Override
	void dimUp(double finalPercent, int seconds) {
		setBrightness(finalPercent);
	}

	private static final Logger log = Logger.getLogger(MethodHandles.lookup().lookupClass().getName());
	
	private String mqttTopicOn;
	private String mqttTopicOff;
	private String mqttValueOn;
	private String mqttValueOff;
	
	private boolean topicsDefined;
	private boolean isOn;
}
