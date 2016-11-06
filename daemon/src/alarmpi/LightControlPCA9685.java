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
public class LightControlPCA9685 implements LightControl,Runnable {

	/**
	 * Constructor
	 */
	public LightControlPCA9685(Configuration.LightControlSettings lightControlSettings) {
		this.lightControlSettings = lightControlSettings;
		
		USABLE_SCALE = lightControlSettings.pwmFullScale-lightControlSettings.pwmOffset;

		if(lightControlSettings.addresses.size()<1) {
			log.severe("PCA9685 light control: No LEDs configured");
			// create a single (dummy) ID
			pwmValue     = new int[1];
			pwmValue[0]  = lightControlSettings.pwmOffset;
		}
		else {
			pwmValue     = new int[lightControlSettings.addresses.size()];
			
			for(int i=0 ; i<lightControlSettings.addresses.size() ; i++) {
				pwmValue[i] = lightControlSettings.pwmOffset;
			}
		}
		
		if(Configuration.getConfiguration().getRunningOnRaspberry()) {
			log.info("Initializing PCA9685 IIC Light Control");
			
			GpioController gpioController = GpioFactory.getInstance();
			GpioPinDigitalOutput output   = gpioController.provisionDigitalOutputPin (RaspiPin.GPIO_27); 
			output.setState(PinState.LOW);
			
			try {
				I2CBus bus = I2CFactory.getInstance(I2CBus.BUS_1);
				
				// reset PCA9685
				I2CDevice pcaReset = bus.getDevice(0x00);
				pcaReset.write((byte)0x06);
				log.info("PCA9685: reset done");
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					log.severe("PCA9685: sleep exception "+e.getMessage());
				}
				
				pca9685 = bus.getDevice(lightControlSettings.address);
				
				// read status registers and dump to logfile
				log.info("PCA9685: MODE1 register: "+pca9685.read(0x00));
				log.info("PCA9685: MODE2 register: "+pca9685.read(0x01));
				
				// turn oscillator on, inversion depends on configuration
				pca9685.write(0x00,(byte) 0x00);
				if(lightControlSettings.pwmInversion) {
					pca9685.write(0x01,(byte) 0x14);
				}
				else {
					pca9685.write(0x01,(byte) 0x04);
				}
			} catch (IOException | UnsupportedBusNumberException e) {
				log.severe("PCA9685 light control: Unable to initialize: "+e.getMessage());
				pca9685 = null;
			}
		}
		else {
			pca9685 = null;
		}

	}
	
	@Override
	public int getCount() {
		return lightControlSettings.addresses.size();
	}
	
	@Override
	public synchronized void off() {
		log.info("pca9685: setting off");
		
		if(dimThread!=null) {
			dimThread.interrupt();
			try {
				dimThread.join();
				dimThread = null;
			} catch (InterruptedException e) {}
		}
		
		if(pca9685!=null) {
			try {
				for(int i=0 ; i<lightControlSettings.addresses.size() ; i++) {
					pca9685.write(0x09+lightControlSettings.addresses.get(i)*4,(byte) 0x10);
					pwmValue[i] = 0;
				}
			} catch (IOException e) {
				log.severe("Error during I2C write: "+e.getMessage());
			}
		}
	}
	
	@Override
	public void setBrightness(double percentage) {
		for(int id=0 ; id<lightControlSettings.addresses.size() ; id++) {
			setBrightness(id, percentage);
		}
	}
	
	@Override
	public void setBrightness(int lightId,double percentage) {
		if(percentage<0) {
			percentage = 0;
		}
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
		
		log.finest("PCA9685: setBrightness on ID "+lightId+": "+percentage+"% pwm="+pwm);;
		setPwm(lightId,pwm);
	}

	@Override
	public double getBrightness(int lightId) {
		double brightness;
		
		if((pwmValue[lightId]-lightControlSettings.pwmOffset)<(int)((8.0/903.3)*(double)USABLE_SCALE)) {
			brightness = ((double)(pwmValue[lightId]-lightControlSettings.pwmOffset)/(double)USABLE_SCALE)*903.3;
		}
		else {
			brightness = 116.0*Math.pow((double)(pwmValue[lightId]-lightControlSettings.pwmOffset)/(double)USABLE_SCALE, 1.0/3.0)-16.0;
		}
		
		log.finest("get Brightness for ID "+lightId+": returns "+brightness);
		
		return brightness;
	}
	
	@Override
	public double getBrightness() {
		return getBrightness(0);
	}
	
	@Override
	public void setPwm(int lightId,int pwmValue) {
		if(pwmValue<0) {
			pwmValue = 0;
		}
		if(pwmValue>lightControlSettings.pwmFullScale) {
			pwmValue = lightControlSettings.pwmFullScale;
		}
		int address = lightControlSettings.addresses.get(lightId);
		log.fine("RaspiPwm: setPWM: lightId="+lightId+" address="+address+" pwm="+pwmValue);
		
		int lsb = (byte)(pwmValue & 0x00FF);
		int msb = (byte)((pwmValue & 0x0F00) >> 8);
		log.fine("PCA9685: setPWM: pwm="+pwmValue+" msb="+msb+" lsb="+lsb);
		
		if(pca9685!=null) {
			try {
				pca9685.write(0x06+4*address,(byte) 0x00);
				pca9685.write(0x07+4*address,(byte) 0x00);
				pca9685.write(0x08+4*address,(byte) lsb);
				pca9685.write(0x09+4*address,(byte) msb);
				
				this.pwmValue[lightId] = pwmValue;
			} catch (IOException e) {
				log.severe("Error during I2C write: "+e.getMessage());
			}
		}
	}
	
	@Override
	public void setPwm(int pwmValue) {
		for(int id=0 ; id<lightControlSettings.addresses.size() ; id++) {
			setPwm(id, pwmValue);
		}
	}

	@Override
	public int getPwm() {
		return pwmValue[0];
	}
	
	@Override
	public void dimUp(double finalPercent,int seconds) {
		// switch off and stop thread in case a thread is still running
		off();
		
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
			setBrightness(p);
			try {
				Thread.sleep(sleepInterval);
			} catch (InterruptedException e) {
				log.fine("PCA9685: dimming thread interrupted");

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
	
	private I2CDevice           pca9685;
	private int                 pwmValue[];              // actual PWM value of each LED controlled thru me
	private Thread              dimThread        = null; // thread used for dimming
	private int                 dimDuration      = 0;    // duration for dim up
	private double              dimTargetPercent = 0;    // target brightness in % for dim up
}
