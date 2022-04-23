package alarmpi;

import java.util.logging.Logger;

import com.pi4j.context.Context;
import com.pi4j.exception.Pi4JException;
import com.pi4j.io.spi.Spi;
import com.pi4j.io.spi.SpiConfig;
import com.pi4j.io.spi.SpiMode;

public class LightControlWS2801 extends LightControl implements Runnable {

	public LightControlWS2801(int id, String name,Context pi4j) {
		super(id, name);
		
		try {
	        SpiConfig spiDeviceConfig = Spi.newConfigBuilder(pi4j)
	                .id("WS2801")
	                .name("WS2801")
	                .mode(SpiMode.MODE_0)
	                .baud(1000000)
	                .address(0)
	                .provider("pigpio-spi")
	                .build();
	        log.fine("spi config created");
			
	        ws2801 = pi4j.create(spiDeviceConfig);
	        log.fine("spi ws2801 device created");
			
			log.info("WS2801 LedStrip initialized");
		}
		catch(Pi4JException e) {
			log.severe("Exception during creation of SPI device: "+e.getMessage());
		}
	}

	@Override
	void setOff() {
		if(dimThread!=null) {
			dimThread.interrupt();
			try {
				dimThread.join();
				dimThread = null;
			} catch (InterruptedException e) {}
		}
		
		setPwm(0);
		
		// publish brightness 0 to MQTT
		lastPubishedBrightness = 0.0;
		MqttClient.getMqttClient().publish(MQTT_TOPIC_BRIGHTNESS, String.format("%.0f",lastPubishedBrightness));

	}

	@Override
	public void setBrightness(double percentage) {
		if(percentage<=0) {
			setOff();
		}
		else {
			if(percentage>100) {
				percentage = 100.0;
			}
			int pwm = 0;
			// found the following relation between physical luminance power and perceived human lightness
			if(percentage<=8.0) {
				pwm = (int)((percentage/903.3)*(double)RANGE);
			}
			else {
				pwm = (int)(Math.pow((percentage+16.0)/116.0, 3.0)*(double)RANGE);
			}
			
			log.finest("setting brightness to "+(int)percentage+"% pwm="+pwm);;
			setPwm(pwm);
			
			if(percentage >= lastPubishedBrightness+10.0) {
				lastPubishedBrightness = percentage;
				MqttClient.getMqttClient().publish(MQTT_TOPIC_BRIGHTNESS, String.format("%.0f",lastPubishedBrightness));
			}
		}
	}

	@Override
	public double getBrightness() {
		double brightness;
		
		if(pwmValue<(int)((8.0/903.3)*(double)RANGE)) {
			brightness = ((double)pwmValue/(double)RANGE)*903.3;
		}
		else {
			brightness = 116.0*Math.pow((double)pwmValue/(double)RANGE, 1.0/3.0)-16.0;
		}
		
		if(brightness<0) {
			brightness = 0.0;
		}
		
		log.finest("get Brightness returns "+brightness);
		
		return brightness;
	}
	

	@Override
	void setPwm(int pwmValue) {
		if(pwmValue<0) {
			pwmValue = 0;
		}
		if(pwmValue>(RANGE-1)) {
			pwmValue = RANGE-1;
		}
		this.pwmValue = pwmValue;
		
		byte data[] = new byte[(LED_COUNT_SKIP_NEAR_END+LED_COUNT_SKIP_FAR_END+LED_COUNT_ACTIVE)*3];
		
		for(int i=0 ; i<LED_COUNT_SKIP_NEAR_END ; i++) {
			data[i*3 + 0] = 0;
			data[i*3 + 1] = 0;
			data[i*3 + 2] = 0;
		}
		
		final double offset[] = {-6.3,-4.2,-1.8,0.3};
		int value[] = new int[4];
		
		for(int i=0 ; i<4 ; i++) {
			value[i] = (byte)(Math.round((double)pwmValue/4.0+offset[i]));
			value[i] = Integer.min(value[i], 255);
			value[i] = Integer.max(value[i], 0);
		}
			
			
		for(int i=0 ; i<LED_COUNT_ACTIVE ; i+=4) {
			for(int j=0 ; j<4 ; j++) {
				int index = i+j;
				if(index < LED_COUNT_ACTIVE) {
					data[(LED_COUNT_SKIP_NEAR_END+index)*3 + 0] = (byte)value[j];
					data[(LED_COUNT_SKIP_NEAR_END+index)*3 + 1] = (byte)Math.round(value[j]*GREEN_BLUE_SCALE);
					data[(LED_COUNT_SKIP_NEAR_END+index)*3 + 2] = (byte)Math.round(value[j]*GREEN_BLUE_SCALE);
				}
			}
		}
		
		for(int i=0 ; i<LED_COUNT_SKIP_FAR_END ; i++) {
			data[(LED_COUNT_SKIP_NEAR_END+LED_COUNT_ACTIVE+i)*3 + 0] = 0;
			data[(LED_COUNT_SKIP_NEAR_END+LED_COUNT_ACTIVE+i)*3 + 1] = 0;
			data[(LED_COUNT_SKIP_NEAR_END+LED_COUNT_ACTIVE+i)*3 + 2] = 0;
		}
		
		try {
			if(ws2801!=null) {
				log.finest("writing to SPI. 10-bit PWM="+pwmValue+" 8-bit PMW values="+value[0]+" "+value[1]+" "+value[2]+" "+value[3]);
				ws2801.write(data);
			}
		}
		catch ( Pi4JException e) {
			log.severe("Exception during spi write: "+e.getMessage());
		}
	}

	@Override
	int getPwm() {
		return pwmValue;
	}

	@Override
	void dimUp(double finalPercent, int seconds) {
		// switch off and stop thread in case a thread is still running
		setOff();
		
		dimDuration      = seconds;
		dimTargetPercent = finalPercent;
		dimThread        = new Thread(this);
		
		log.fine("starting dimming thread");
		dimThread.start();
	}
	
	@Override
	public void run() {
		log.fine("dim up thread started, range="+RANGE+" duration="+dimDuration+"s target="+dimTargetPercent+"%");
		
		// increase brightness once per second
		double stepSize = dimTargetPercent/(double)dimDuration;
		
		log.fine("dim up thread stepSize="+stepSize);
		for(double p=0 ; p<=dimTargetPercent ; p+=stepSize) {
			// only increase brightness
			if(p>getBrightness()) {
				setBrightness(p);
			}
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				log.info("dimming thread interrupted");

				return;
			}
		}
		log.fine("dim up thread finished");
		
		dimThread = null;
	}

	
	// private members
	private static final Logger log = Logger.getLogger( LightControlWS2801.class.getName() );
	
	private static final int RANGE      = 1023;             // 8 real bits, plus 2 virtual bits by distributing over mutliple LEDs
	
	private static final int LED_COUNT_SKIP_FAR_END   = 10;
	private static final int LED_COUNT_SKIP_NEAR_END  = 20;
	private static final int LED_COUNT_ACTIVE = 70;
	private static final double GREEN_BLUE_SCALE = 0.6;
	
	private int                    pwmValue;                // actual PWM value of each LED controlled thru me
	private Thread                 dimThread        = null; // thread used for dimming
	private int                    dimDuration      = 0;    // duration for dim up in seconds
	private double                 dimTargetPercent = 0;    // target brightness in % for dim up
	
	private Spi                    ws2801 = null;           // PI4J SPI device object
	
	private final static String    MQTT_TOPIC_BRIGHTNESS  = "brightness";  // MQTT topic to publish LED brightness
	private double       lastPubishedBrightness           = 0.0;           // last brightness value (percent) that was published on MQTT
}
