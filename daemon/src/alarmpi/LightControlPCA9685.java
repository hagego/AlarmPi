package alarmpi;

import java.util.logging.Logger;

import com.pi4j.context.Context;
import com.pi4j.exception.Pi4JException;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.io.i2c.I2C;
import com.pi4j.io.i2c.I2CConfig;

/**
 * LighControl implementation for NXP PCA9685
 */
public class LightControlPCA9685 extends LightControl implements Runnable {

	/**
	 * Constructor
	 */
	public LightControlPCA9685(Configuration.LightControlSettings lightControlSettings,Context pi4j) {
		super(lightControlSettings.id,lightControlSettings.name);
		
		this.lightControlSettings = lightControlSettings;
		this.pi4j = pi4j;
		
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
				
				final int IIC_BUS       = 0x1;
				final int RESET_ADDRESS = 0x0;
				
				// initialize pi4j objects for GPIO handling
				if(Configuration.getConfiguration().getRunningOnRaspberry()) {
					log.info("running on Raspberry - initializing pi4j IIC access");
			        
			        try {
			            I2CConfig i2cDeviceConfigReset = I2C.newConfigBuilder(pi4j)
			                    .bus(IIC_BUS)
			                    .device(RESET_ADDRESS)
			                    .id("PCA9685_reset")
			                    .name("PCA9685_reset")
			                    .provider("pigpio-i2c")
			                    .build();
			            log.fine("i2c config created");;
			        
			            I2C resetDevice = pi4j.create(i2cDeviceConfigReset);
			            log.fine("i2c reset device created");;
			            
			            resetDevice.write((byte) 0x06);
			            resetDevice.close();
			            log.fine("PCA9685: reset done");
			            
			            try {
							Thread.sleep(100);
						} catch (InterruptedException e) {
							log.severe("PCA9685: sleep exception "+e.getMessage());
						}
			            
			            I2CConfig config = I2C.newConfigBuilder(pi4j)
			                    .bus(IIC_BUS)
			                    .device(lightControlSettings.deviceAddress)
			                    .id("PCA9685")
			                    .name("PCA9685")
			                    .provider("pigpio-i2c")
			                    .build();
			        
			            pca9685 = pi4j.create(config);
			            log.fine("created i2c device with device address "+lightControlSettings.deviceAddress);
			        } catch (Pi4JException e) {
			            log.severe("Exception during creation of IIC objects: "+e.getMessage());
			        } catch (Exception e) {
			        	log.severe("Exception during pi4j initialization: "+e.getMessage());
			        	for( var v:e.getStackTrace()) {
			        		log.severe(v.toString());
			        	}
			        }
			        
			        // read status registers and dump to logfile
					log.fine("PCA9685: MODE1 register after reset: "+pca9685.readRegisterByte(0x00));
					log.fine("PCA9685: MODE2 register after reset: "+pca9685.readRegisterByte(0x01));
					
					// turn oscillator on at 1.5MHz, inversion depends on configuration
					pca9685.writeRegister(0x00,(byte) 0x10);
					pca9685.writeRegister(0xFE,(byte) 0x05);
					pca9685.writeRegister(0x00,(byte) 0x00);
					if(lightControlSettings.pwmInversion) {
						pca9685.writeRegister(0x01,(byte) 0x15);
					}
					else {
						pca9685.writeRegister(0x01,(byte) 0x05);
					}
					
					log.fine("PCA9685: MODE1 register after setup: "+pca9685.readRegisterByte(0x00));
					log.fine("PCA9685: MODE2 register after setup: "+pca9685.readRegisterByte(0x01));
					
					// all LEDs off
					pca9685.writeRegister(0xFA,(byte) 0x00);
					pca9685.writeRegister(0xFB,(byte) 0x00);
					pca9685.writeRegister(0xFC,(byte) 0x00);
					pca9685.writeRegister(0xFD,(byte) 0x10);
					

					try {
						int                  GPIO_OUTPUT = 16; // GPIO number for XX
						
			            log.fine("digital output provider created");;
			            
						var outputConfig = DigitalOutput.newConfigBuilder(pi4j)
			            	      .id("PCA9685 Output")
			            	      .name("PCA9685 Output")
			            	      .address(GPIO_OUTPUT)
			            	      .shutdown(DigitalState.LOW)
			            	      .initial(DigitalState.LOW)
			            	      .provider("pigpio-digital-output");
			            	      
			            var output = pi4j.create(outputConfig);
			            output.low();
					}
					catch(Pi4JException e) {
						log.severe("Exception during setting OE pin low using pi4j");
						log.severe(e.getMessage());
					}
					
					log.info("running on Raspberry - initializing WiringPi done.");
				}
				else {
					pi4j = null;
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
				pca9685.writeRegister(0x09+lightControlSettings.ledId*4,(byte) 0x10);
			} catch (Pi4JException e) {
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
				pca9685.writeRegister(0x08+4*address,(byte) lsb);
				pca9685.writeRegister(0x09+4*address,(byte) msb);
			} catch (Pi4JException e) {
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

	private Context      pi4j                       = null; // pi4j context
	private static       I2C pca9685                = null; // pi4j I2C device
	
	private int          pwmValue;                          // actual PWM value of each LED controlled thru me
	private Thread       dimThread                  = null; // thread used for dimming
	private int          dimDuration      = 0;              // duration for dim up
	private double       dimTargetPercent = 0;              // target brightness in % for dim up
	
	private final static String    MQTT_TOPIC_BRIGHTNESS  = "brightness";  // MQTT topic to publish LED brightness
	private double       lastPubishedBrightness           = 0.0;           // last brightness value (percent) that was published on MQTT
}
 