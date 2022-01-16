package alarmpi;

import java.io.IOException;
import java.util.logging.Logger;

import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.PinState;
import com.pi4j.io.gpio.RaspiPin;
import com.pi4j.io.i2c.I2CBus;
import com.pi4j.io.i2c.I2CDevice;
import com.pi4j.io.i2c.I2CFactory;
import com.pi4j.io.i2c.I2CFactory.UnsupportedBusNumberException;

/**
 * LighControl implementation for NXP PCA9685
 */
public class LightControlPCA9685 extends LightControl implements Runnable {

	/**
	 * Constructor
	 */
	public LightControlPCA9685(Configuration.LightControlSettings lightControlSettings) {
		super(lightControlSettings.id,lightControlSettings.name);
		
		this.lightControlSettings = lightControlSettings;
		
		USABLE_SCALE = lightControlSettings.pwmFullScale-lightControlSettings.pwmOffset;
		pwmValue  = lightControlSettings.pwmOffset;
		
		initializeDevice();
		
		log.info("Initializing PCA9685 IIC Light Control done.");
	}
	
	private synchronized void initializeDevice() {
		// initialization of the I2C device must happen only once
		if(pca9685==null) {
			if(Configuration.getConfiguration().getRunningOnRaspberry()) {
				log.info("Initializing PCA9685 IIC Light Control");
				
				try {
					I2CBus bus = I2CFactory.getInstance(I2CBus.BUS_1);
					
					// reset PCA9685
					I2CDevice pcaReset = bus.getDevice(0x00);
					pcaReset.write((byte)0x06);
					log.fine("PCA9685: reset done");
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						log.severe("PCA9685: sleep exception "+e.getMessage());
					}
					
					pca9685 = bus.getDevice(lightControlSettings.deviceAddress);
					
					// read status registers and dump to logfile
					log.fine("PCA9685: MODE1 register after reset: "+pca9685.read(0x00));
					log.fine("PCA9685: MODE2 register after reset: "+pca9685.read(0x01));
					
					// turn oscillator on at 1.5MHz, inversion depends on configuration
					pca9685.write(0x00,(byte) 0x10);
					pca9685.write(0xFE,(byte) 0x05);
					pca9685.write(0x00,(byte) 0x00);
					if(lightControlSettings.pwmInversion) {
						pca9685.write(0x01,(byte) 0x15);
					}
					else {
						pca9685.write(0x01,(byte) 0x05);
					}
					
					log.fine("PCA9685: MODE1 register after setup: "+pca9685.read(0x00));
					log.fine("PCA9685: MODE2 register after setup: "+pca9685.read(0x01));
					
					// all LEDs off
					pca9685.write(0xFA,(byte) 0x00);
					pca9685.write(0xFB,(byte) 0x00);
					pca9685.write(0xFC,(byte) 0x00);
					pca9685.write(0xFD,(byte) 0x10);
					
					GpioController gpioController = GpioFactory.getInstance();
					
					GpioPinDigitalOutput output = null;;
					
					// there seems to be a known, intermittent issue with the timing inside this code...
					// https://github.com/raspberrypi/linux/issues/553
					// allow one retry
					try {
						output   = gpioController.provisionDigitalOutputPin (RaspiPin.GPIO_27);
					}
					catch(Throwable e) {
						Thread.sleep(100);
						output   = gpioController.provisionDigitalOutputPin (RaspiPin.GPIO_27);
					}
					
					output.setState(PinState.LOW);
				} catch (IOException | UnsupportedBusNumberException e) {
					log.severe("PCA9685 light control: Unable to initialize: "+e.getMessage());
					pca9685 = null;
				}
				catch(Throwable e) {
					log.severe("Uncaught runtime exception during initialization of PCS9685 light control: "+e.getMessage());
					log.severe(e.getCause().toString());
					for(StackTraceElement element:e.getStackTrace()) {
		    			log.severe(element.toString());
		    		}
				}
			}
		}
	}
	
	@Override
	public synchronized void setOff() {
		log.fine("pca9685: setting off");
		
		if(dimThread!=null) {
			dimThread.interrupt();
			try {
				dimThread.join();
				dimThread = null;
			} catch (InterruptedException e) {}
		}
		
		if(pca9685!=null) {
			try {
				pwmValue = 0;
				pca9685.write(0x09+lightControlSettings.ledId*4,(byte) 0x10);
			} catch (IOException e) {
				log.severe("Error during I2C write: "+e.getMessage());
			}
		}
		
		lastPubishedBrightness = 0.0;
		MqttClient.getMqttClient().publish(MQTT_TOPIC_BRIGHTNESS, String.format("%.0f",lastPubishedBrightness));
		
		log.fine("pca9685: setting off done");
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
				pwm = lightControlSettings.pwmOffset+(int)((percentage/903.3)*(double)USABLE_SCALE);
			}
			else {
				pwm = lightControlSettings.pwmOffset+(int)(Math.pow((percentage+16.0)/116.0, 3.0)*(double)USABLE_SCALE);
			}
			
			log.finest("PCA9685: setBrightness to "+percentage+"% pwm="+pwm);;
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
		
		if((pwmValue-lightControlSettings.pwmOffset)<(int)((8.0/903.3)*(double)USABLE_SCALE)) {
			brightness = ((double)(pwmValue-lightControlSettings.pwmOffset)/(double)USABLE_SCALE)*903.3;
		}
		else {
			brightness = 116.0*Math.pow((double)(pwmValue-lightControlSettings.pwmOffset)/(double)USABLE_SCALE, 1.0/3.0)-16.0;
		}
		
		if(brightness<0) {
			brightness = 0.0;
		}
		
		log.finest("get Brightness returns "+brightness);
		
		return brightness;
	}
	
	@Override
	public void setPwm(int pwmValue) {
		if(pwmValue<0) {
			pwmValue = 0;
		}
		if(pwmValue>lightControlSettings.pwmFullScale) {
			pwmValue = lightControlSettings.pwmFullScale;
		}
		this.pwmValue = pwmValue;
		
		int address = lightControlSettings.ledId;
		log.finest("RaspiPwm: setPWM: address="+address+" pwm="+pwmValue);
		
		int lsb = (byte)(pwmValue & 0x00FF);
		int msb = (byte)((pwmValue & 0x0F00) >> 8);
		log.finest("PCA9685: setPWM: pwm="+pwmValue+" msb="+msb+" lsb="+lsb);
		
		if(pca9685!=null) {
			try {
//				pca9685.write(0x06+4*address,(byte) 0x00);
//				pca9685.write(0x07+4*address,(byte) 0x00);
				pca9685.write(0x08+4*address,(byte) lsb);
				pca9685.write(0x09+4*address,(byte) msb);
			} catch (IOException e) {
				log.severe("Error during I2C write: "+e.getMessage());
			}
		}
	}
	
	@Override
	public int getPwm() {
		return pwmValue;
	}
	
	@Override
	public void dimUp(double finalPercent,int seconds) {
		// switch off and stop thread in case a thread is still running
		setOff();
		
		dimDuration      = seconds;
		dimTargetPercent = finalPercent;
		dimThread        = new Thread(this);
		
		log.fine("PCA9685: starting dimming thread");
		dimThread.start();
	}
	
	@Override
	public void run() {
		log.fine("PCA9685: dim up thread started, duration="+dimDuration+" target="+dimTargetPercent+"%");
		
		double stepSize = dimTargetPercent/(double)DIM_STEP_COUNT;
		long   sleepInterval = (long)(1E3*dimDuration/(double)DIM_STEP_COUNT);
		
		log.fine("PCA9685: dim up thread stepSize="+stepSize+" sleep interval="+sleepInterval);
		for(double p=0 ; p<=dimTargetPercent ; p+=stepSize) {
			// only increase brightness
			if(p>getBrightness()) {
				setBrightness(p);
			}
			try {
				Thread.sleep(sleepInterval);
			} catch (InterruptedException e) {
				log.info("PCA9685: dimming thread interrupted");

				return;
			}
		}
		log.fine("PCA9685: dim up thread finished");
		
		dimThread = null;
	}

	//
	// private members
	//
	private static final Logger log = Logger.getLogger( LightControlPCA9685.class.getName() );
	
	private final        Configuration.LightControlSettings lightControlSettings;
	private final        int                                USABLE_SCALE;
	private final        int                                DIM_STEP_COUNT = 150;
	
	private static       I2CDevice pca9685          = null;
	private int                    pwmValue;                // actual PWM value of each LED controlled thru me
	private Thread                 dimThread        = null; // thread used for dimming
	private int                    dimDuration      = 0;    // duration for dim up
	private double                 dimTargetPercent = 0;    // target brightness in % for dim up
	
	private final static String    MQTT_TOPIC_BRIGHTNESS  = "brightness";  // MQTT topic to publish LED brightness
	private double       lastPubishedBrightness           = 0.0;           // last brightness value (percent) that was published on MQTT
}
