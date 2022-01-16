package alarmpi;

import java.io.IOException;
import java.util.logging.Logger;

import com.pi4j.io.spi.SpiChannel;
import com.pi4j.io.spi.SpiDevice;
import com.pi4j.io.spi.SpiFactory;
import com.pi4j.io.spi.SpiMode;

public class LightControlWS2801 extends LightControl implements Runnable {

	public LightControlWS2801(int id, String name) {
		super(id, name);
		// TODO Auto-generated constructor stub
		
		// setup SPI
		try {
			spi = SpiFactory.getInstance(SpiChannel.CS0,1000000,SpiMode.MODE_0);
		} catch (IOException e) {
			log.severe("Error during SPI initialize: "+e.getMessage());
		}
		
		log.info("LedStripTrial initialized");
	}

	@Override
	void setOff() {
		// TODO Auto-generated method stub

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
			
			log.finest("setting brightness to "+percentage+"% pwm="+pwm);;
			setPwm(pwm);
			
//			if(percentage >= lastPubishedBrightness+10.0) {
//				lastPubishedBrightness = percentage;
//				String topic = Configuration.getConfiguration().getValue("mqtt", "publishTopicBrightness", null);
//				if(topic != null) {
//					MqttClient.getMqttClient().publish(topic, String.format("%.0f",lastPubishedBrightness));
//				}
//			}
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
		
		int baseValue = pwmValue / GROUP_SIZE;
		int increasedValueCount = pwmValue % GROUP_SIZE;
		
		log.finest("setting PWM to "+pwmValue+" base value="+baseValue+", increased value on "+increasedValueCount+"/"+GROUP_SIZE+" LEDs");

		short data[] = new short[(LED_COUNT_SKIP_NEAR_END+LED_COUNT_SKIP_FAR_END+LED_COUNT_ACTIVE)*3];
		
		for(int i=0 ; i<LED_COUNT_SKIP_NEAR_END ; i++) {
			data[i*3 + 0] = 0;
			data[i*3 + 1] = 0;
			data[i*3 + 2] = 0;
		}
		
		for(int i=0 ; i<LED_COUNT_ACTIVE ; i++) {
			data[(LED_COUNT_SKIP_NEAR_END+i)*3 + 0] = (short)baseValue;
			data[(LED_COUNT_SKIP_NEAR_END+i)*3 + 1] = (short)(baseValue*GREEN_BLUE_SCALE);
			data[(LED_COUNT_SKIP_NEAR_END+i)*3 + 2] = (short)(baseValue*GREEN_BLUE_SCALE);
		}
		
		for(int i=0 ; i<LED_COUNT_SKIP_FAR_END ; i++) {
			data[(LED_COUNT_SKIP_NEAR_END+LED_COUNT_ACTIVE+i)*3 + 0] = 0;
			data[(LED_COUNT_SKIP_NEAR_END+LED_COUNT_ACTIVE+i)*3 + 1] = 0;
			data[(LED_COUNT_SKIP_NEAR_END+LED_COUNT_ACTIVE+i)*3 + 2] = 0;
		}
		
		try {
			spi.write(data,0,data.length);
		} catch (IOException e) {
			log.severe("Error during SPI write: "+e.getMessage());
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
	
	private static final int PWM_BITS   = 8;                // number of PWM bits in WS2801
	private static final int GROUP_BITS = 2;                // additional (LSB) bits thru grouping
	private static final int GROUP_SIZE = 1 << GROUP_BITS;  // group size for simulated LSBs
	private static final int RANGE      = 1 << (PWM_BITS+GROUP_BITS); // number of possible steps (range)
	
	private static final int LED_COUNT_SKIP_FAR_END   = 50;
	private static final int LED_COUNT_SKIP_NEAR_END  = 3;
	private static final int LED_COUNT_ACTIVE = 8;
	private static final double GREEN_BLUE_SCALE = 0.6;
	
	private int                    pwmValue;                // actual PWM value of each LED controlled thru me
	private Thread                 dimThread        = null; // thread used for dimming
	private int                    dimDuration      = 0;    // duration for dim up in seconds
	private double                 dimTargetPercent = 0;    // target brightness in % for dim up
	
	SpiDevice                      spi              = null; // PI4J SPI device
}
