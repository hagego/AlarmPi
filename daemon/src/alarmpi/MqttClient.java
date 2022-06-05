package alarmpi;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;


/**
 * MQTT Client
 */
public class MqttClient implements MqttCallbackExtended{
	
	/**
	 * private constructor
	 * @param brokerAddress MQTT broker address
	 * @param brokerPort    MQTT broker port
	 */
	private MqttClient(String brokerAddress, int brokerPort, int keepalive, String clientId) {
			try {
				MqttConnectOptions connectOptions = new MqttConnectOptions();
				connectOptions.setAutomaticReconnect(true);
				connectOptions.setKeepAliveInterval(keepalive);
				
				isConnected = new AtomicBoolean(false);

				String broker = "tcp://"+brokerAddress+":"+brokerPort;
				log.info("Creating MQTT client for broker "+broker+", client ID="+clientId);
				mqttClient = new org.eclipse.paho.client.mqttv3.MqttAsyncClient(broker, clientId,new MemoryPersistence());
				log.info("client created");
				mqttClient.setCallback(this);
				
				// maintain list of topics to subscribe for automatic reconnect
				topicList = new LinkedList<Topic>();
				
				log.fine("Connecting to MQTT broker "+brokerAddress);
				mqttClient.connect(connectOptions);
				
				// wait until connection is successful, or time out
				final int TIMEOUT=10;
				int i;
				for(i=0 ; i<TIMEOUT && isConnected.get()==false ; i++) {
					Thread.sleep(500);
				}
				
				if(i<TIMEOUT) {
					log.info("MQTT client connected");
				}
				else {
					log.severe("MQTT client connect timeout");
				}
			} catch (MqttException e) {
				log.severe("Excepion during MQTT connect: "+e.getMessage());
				log.severe("Excepion during MQTT connect reason="+e.getReasonCode());
			} catch (InterruptedException e) {
				log.severe("MQTT client waiting for connection got interrupted");
				log.severe(e.getMessage());
			}
	}
	
	/**
	 * @return the singleton MQTT client object if a MQTT broker is specified in the configuration
	 *         or null otherwise 
	 */
	public synchronized static MqttClient getMqttClient() {
		if(theObject==null) {
			log.fine("Creating new MqttClient");
			
			if(Configuration.getConfiguration().getValue("mqtt", "address",null)!=null) {
				theObject = new MqttClient(Configuration.getConfiguration().getMqttAddress(),
						                   Configuration.getConfiguration().getMqttPort(),
						                   Configuration.getConfiguration().getMqttKeepalive(),
						                   Configuration.getConfiguration().getName());
			}
		}
		
		return theObject;
	}
	
	/**
	 * subscribes for an MQTT topic
	 * @param topicName  topic to subscribe
	 * @param listener   listener objects for the callbacl
	 */
	public synchronized void subscribe(String topicName,IMqttMessageListener listener) {
		String fqt = getFullyQualifiedTopic(topicName);
		
		if(mqttClient==null) {
			log.severe("Unable to subscribe for topic "+fqt+": MQTT client not created");
			
			return;
		}
		log.fine("subscribing for MQTT topic: "+fqt);
		
		// add to list of topics in case we have to reconnect
		Topic topic = new Topic();
		topic.topic    = fqt;
		topic.listener = listener;

		if(topicList.contains(topic)) {
			log.warning("MQTT subscription of topic "+fqt+": topic already subscribed");
		}
		else {
			topicList.add(topic);
		}
		
		// subscribe if we are currently connected
		if(isConnected.get()) {
			try {
				mqttClient.subscribe(fqt,0,listener);
			} catch (MqttException e) {
				log.severe("MQTT subscribe for topic "+fqt+" failed: "+e.getMessage());
			}
		}
	}
	
	/**
	 * publishes an MQTT topic
	 * @param topic  topic to publish to
	 * @param data   data to publish
	 */
	public void publish(String topic,String data) {
		if(topic==null || topic.length()==0) {
			log.severe("Invalid topic name to publish: "+topic);
			
			return;
		}
				
		if(data==null) {
			data = new String();
		}
		
		try {
			mqttClient.publish(getFullyQualifiedTopic(topic), data.getBytes(), 0, false);
		} catch (MqttException e) {
			log.severe("Unable to publish MQTT topic "+topic+", data="+data);
			log.severe(e.getMessage());
		}
	}
	
	private String getFullyQualifiedTopic(String topic) {
		String fullTopic;
		String prefix = Configuration.getConfiguration().getMqttTopicPrefix();
		
		if(prefix==null || prefix.length()==0) {
			fullTopic = topic;
		}
		else {
			if(prefix.endsWith("/")) {
				fullTopic = prefix+topic;
			}
			else {
				fullTopic = prefix+"/"+topic;
			}
		}
		
		return fullTopic;
	}

	@Override
	public void connectionLost(Throwable t) {
		isConnected.set(false);
		
		log.severe("connection to MQTT broker lost: "+t.getMessage());
		if(t.getCause()!=null) {
			log.severe("connection to MQTT broker lost cause: "+t.getCause().getMessage());
		}
	}

	@Override
	public void deliveryComplete(IMqttDeliveryToken t) {
	}

	@Override
	public void messageArrived(String topic, MqttMessage message) throws Exception {
		log.fine("MQTT message arrived: topic="+topic);
		log.finest("MQTT message arrived: topic="+topic+" content="+message);
	}

	@Override
	public synchronized void connectComplete(boolean reconnect, String serverURI) {
		isConnected.set(true);
		log.info("connection to MQTT broker completed. reconnect="+reconnect);

		// in case of reconnect loop over all topics and subscribe again
		for(Topic topic:topicList) {
			log.info("(re-)subscribing to MQTT topic "+topic.topic);
			
			// subscribe
			try {
				mqttClient.subscribe(topic.topic,0,topic.listener);
			} catch (MqttException e) {
				log.severe("MQTT subscribe for topic "+topic.topic+" failed: "+e.getMessage());
			}
		}
	}
	
	//
	// private data members
	//
	private static final Logger log = Logger.getLogger( MqttClient.class.getName() );
	
	private static  MqttClient                                     theObject = null;
	private         org.eclipse.paho.client.mqttv3.MqttAsyncClient mqttClient;
	
	// private class to hold topics to subscribe together with their listeners
	private class Topic {
		String               topic;
		IMqttMessageListener listener;
		
		@Override
		public boolean equals(Object obj) {
			if(obj==null) {
				return false;
			}
			Topic t = (Topic)obj;
			return topic.equals(t.topic) && listener.equals(t.listener);
		}
	};
	
	private List<Topic>   topicList;              // list of subscribed topics
	private AtomicBoolean isConnected;            // maintains if client is currently connected or not
}

