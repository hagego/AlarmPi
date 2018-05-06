package alarmpi;

import java.time.LocalDateTime;
import java.util.logging.Logger;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

public class MqttClient implements MqttCallbackExtended{
	
	/**
	 * private constructor
	 * @param brokerAddress MQTT broker address
	 * @param brokerPort    MQTT broker port
	 */
	private MqttClient(String brokerAddress, int brokerPort) {
			try {
				MqttConnectOptions connectOptions = new MqttConnectOptions();
				connectOptions.setAutomaticReconnect(true);
				
				mqttClient = new org.eclipse.paho.client.mqttv3.MqttAsyncClient("tcp://"+brokerAddress+":"+brokerPort,Configuration.getConfiguration().getName());
				mqttClient.setCallback(this);
				
				log.fine("Connecting to MQTT broker "+brokerAddress);
				mqttClient.connect(connectOptions);
			} catch (MqttException e) {
				log.severe("Excepion during MQTT connect: "+e.getMessage());
			}
	}
	
	/**
	 * @return the singleton MQTT client object if a MQTT broker is specified in the configuration
	 *         or null otherwise 
	 */
	public static MqttClient getMqttClient() {
		if(theObject==null) {
			log.fine("Creating new MqttClient");
			
			if(Configuration.getConfiguration().getMqttAddress()!=null && Configuration.getConfiguration().getMqttPort()!=null) {
				theObject = new MqttClient(Configuration.getConfiguration().getMqttAddress(), Configuration.getConfiguration().getMqttPort());
			}
		}
		
		return theObject;
	}

	@Override
	public void connectionLost(Throwable t) {
		log.severe("connection to MQTT broker lost: "+t.getMessage());
	}

	@Override
	public void deliveryComplete(IMqttDeliveryToken t) {
	}

	@Override
	public void messageArrived(String topic, MqttMessage message) throws Exception {
		log.fine("MQTT message arrived: topic="+topic+" content="+message);
		
		if(Configuration.getConfiguration().getMqttSubscribeTopicTemperature()!=null
				&& topic.equals(Configuration.getConfiguration().getMqttSubscribeTopicTemperature())) {
			log.fine("MQTT temperature update message arrived. content="+message);
			
			temperature = Double.parseDouble(message.toString());
			temperatureLastUpdate = LocalDateTime.now();
		}
	}

	@Override
	public void connectComplete(boolean reconnect, String serverURI) {
		log.info("connection to MQTT broker completed. reconnect="+reconnect);
		
		log.fine("subscribing to MQTT topic "+Configuration.getConfiguration().getMqttSubscribeTopicTemperature());
		try {
			mqttClient.subscribe(Configuration.getConfiguration().getMqttSubscribeTopicTemperature(),0);
		} catch (MqttException e) {
			log.severe("Excepion during MQTT connect: "+e.getMessage());
		}
	}
	
	public void publishLongClick() {
		if(Configuration.getConfiguration().getMqttPublishTopicLongClick()!=null) {
			try {
				mqttClient.publish(Configuration.getConfiguration().getMqttPublishTopicLongClick(), new byte[0], 0, false);
			} catch (MqttException e) {
				log.severe("Exception during MQTT publis: "+e.getMessage());
			}
		}
	}
	
	/**
	 * @return The actual temperature as retrieved from the MQTT broker, or null if the MQTT broker
	 *         did not publish a new temperature since more than 2h
	 */
	public Double getTemperature() {
		if( temperatureLastUpdate==null || LocalDateTime.now().minusHours(2).isAfter(temperatureLastUpdate) ) {
			log.warning("last temperature update is null or older than 2h");
			return null;
		}
		
		return temperature;
	}
	
	//
	// private data members
	//
	private static final Logger log = Logger.getLogger( MqttClient.class.getName() );
	
	private static       MqttClient    theObject = null;

	org.eclipse.paho.client.mqttv3.MqttAsyncClient mqttClient;
	private Double        temperature;
	private LocalDateTime temperatureLastUpdate;
}

