package alarmpi;

import java.io.IOException;
import java.util.logging.Logger;
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
		
		if(Configuration.getConfiguration().getRunningOnRaspberry()) {
			log.info("Initializing PCA9685 IIC Light Control");
			
			/**
			not used - OE does not work as expected
			
			GpioController gpioController       = GpioFactory.getInstance();
			GpioPinDigitalOutput output = gpioController.provisionDigitalOutputPin (RaspiPin.GPIO_16); // BRCM GPIO 15, UARTRX0
			output.setState(PinState.HIGH);
			**/
			
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
				
				pca9685 = bus.getDevice(0x45);
				
				// read status registers and dump to logfile
				log.info("PCA9685: MODE1 register: "+pca9685.read(0x00));
				log.info("PCA9685: MODE2 register: "+pca9685.read(0x01));
				
				// turn oscillator and inversion on
				pca9685.write(0x00,(byte) 0x00);
				pca9685.write(0x01,(byte) 0x14);
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
				for(Integer address:lightControlSettings.addresses) {
					pca9685.write(0x09+address*4,(byte) 0x10);
				}
				pwmValue = 0;
			} catch (IOException e) {
				log.severe("Error during I2C write: "+e.getMessage());
			}
		}
	}
	
	@Override
	public void setBrightness(double percentage) {
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
		
		log.finest("PCA9685: setBrightness="+percentage+"% pwm="+pwm);;
		setPwm(pwm);
	}

	@Override
	public double getBrightness() {
		if((pwmValue-lightControlSettings.pwmOffset)<(int)((8.0/903.3)*(double)USABLE_SCALE)) {
			return ((double)(pwmValue-lightControlSettings.pwmOffset)/(double)USABLE_SCALE)*903.3;
		}
		else {
			return 116.0*Math.pow((double)(pwmValue-lightControlSettings.pwmOffset)/(double)USABLE_SCALE, 1.0/3.0)-16.0;
		}
	}
	
	@Override
	public void setPwm(int pwmValue) {
		if(pwmValue<0) {
			pwmValue = 0;
		}
		if(pwmValue>lightControlSettings.pwmFullScale) {
			pwmValue = lightControlSettings.pwmFullScale;
		}
		log.fine("RaspiPwm: setPWM: pwm="+pwmValue);
		
		byte lsb = (byte)(pwmValue & 0x00FF);
		byte msb = (byte)((pwmValue & 0x0F00) >> 8);
		log.fine("PCA9685: setPWM: pwm="+pwmValue+" msb="+msb+" lsb="+lsb);
		
		if(pca9685!=null) {
			try {
				for(Integer address:lightControlSettings.addresses) {
					pca9685.write(0x06+4*address,(byte) 0x00);
					pca9685.write(0x07+4*address,(byte) 0x00);
					pca9685.write(0x08+4*address,(byte) lsb);
					pca9685.write(0x09+4*address,(byte) msb);
				}
				
				this.pwmValue = pwmValue;
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
	private final        int                                DIM_STEP_COUNT = 200;
	
	private I2CDevice           pca9685;
	private int                 pwmValue;                // actual PWM value
	private Thread              dimThread        = null; // thread used for dimming
	private int                 dimDuration      = 0;    // duration for dim up
	private double              dimTargetPercent = 0;    // target brightness in % for dim up
}
