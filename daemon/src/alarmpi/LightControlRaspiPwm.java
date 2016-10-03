package alarmpi;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.logging.Logger;

import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;
import com.pi4j.io.gpio.GpioPinPwmOutput;
import com.pi4j.io.gpio.RaspiPin;

/**
 * Implements LightControl using Raspberry gpio18 PWM
 * This was useful for older Versions of HiFiBerry that did not use gpio18
 * It does not work for HiFiberry DAC+
 */
public class LightControlRaspiPwm implements LightControl,Runnable {

	public LightControlRaspiPwm(Configuration.LightControlSettings lightControlSettings) {
		this.lightControlSettings = lightControlSettings;
		
		USABLE_SCALE = lightControlSettings.pwmFullScale-lightControlSettings.pwmOffset;
		
		if(Configuration.getConfiguration().getRunningOnRaspberry()) {
			log.info("Initializing Raspberry PWM Light Control");
			
			GpioController gpioController = GpioFactory.getInstance();
			gpioPinLedPwm = gpioController.provisionPwmOutputPin(RaspiPin.GPIO_01);
			
			// couldn't find a better way to do this, but it seems to be needed
			try {
				Process p;
				// search in /usr/bin first (gpio is part of more recent Raspian versions)
				if(Files.isExecutable(FileSystems.getDefault().getPath("/usr/bin/gpio"))) {
					p = Runtime.getRuntime().exec("/usr/bin/gpio pwm-ms");
				}
				else {
					p = Runtime.getRuntime().exec("/usr/local/bin/gpio pwm-ms");
				}
				p.waitFor();
			} catch (IOException | InterruptedException e) {
				log.severe(e.getMessage());
			}
			
		}
		else {
			gpioPinLedPwm = null;
		}
	}
	
	@Override
	public void off() {
		log.info("RaspiPwm: setting off");
		
		if(dimThread!=null) {
			dimThread.interrupt();
			try {
				dimThread.join();
			} catch (InterruptedException e) {}
			dimThread = null;
		}
		
		if(gpioPinLedPwm!=null) {
			setPwm(0);
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
		
		log.finest("RaspiPwm: setBrightness="+percentage+"% pwm="+pwm);;
		setPwm(pwm);
	}
	
	@Override
	public void setBrightness(int lightId,double percentage) {
		setBrightness(percentage);
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
	public double getBrightness(int lightId) {
		return getBrightness();
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
		
		if(gpioPinLedPwm!=null) {
			if(lightControlSettings.pwmInversion) {
				gpioPinLedPwm.setPwm(lightControlSettings.pwmFullScale-pwmValue);
			}
			else {
				gpioPinLedPwm.setPwm(pwmValue);
			}
				
			this.pwmValue = pwmValue;
		}
	}
	
	@Override
	public void setPwm(int lightId,int pwmValue) {
		setPwm(pwmValue);
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
		
		log.fine("RaspiPwm: starting dimming thread");
		dimThread.start();
	}
	
	@Override
	public void run() {
		log.fine("RaspiPwm: dim up thread started, duration="+dimDuration+" target="+dimTargetPercent+"%");
		
		double stepSize = dimTargetPercent/(double)DIM_STEP_COUNT;
		long   sleepInterval = (long)(1E3*dimDuration/(double)DIM_STEP_COUNT);
		
		log.fine("RaspiPwm: dim up thread stepSize="+stepSize+" sleep intervale="+sleepInterval);
		for(double p=0 ; p<=dimTargetPercent ; p+=stepSize) {
			setBrightness(p);
			try {
				Thread.sleep(sleepInterval);
			} catch (InterruptedException e) {
				log.fine("RaspiPwm: dimming thread interrupted");
				
				return;
			}
		}
		log.fine("RaspiPwm: dim up thread finished");
		
		dimThread = null;
	}

	//
	// private members
	//
	private static final Logger log = Logger.getLogger( LightControlRaspiPwm.class.getName() );
	private final        Configuration.LightControlSettings lightControlSettings;
	private final        int                                USABLE_SCALE;
	private final        int                                DIM_STEP_COUNT = 200;
	
	private GpioPinPwmOutput    gpioPinLedPwm    = null; // WiringPi Pin GPIO01 = BRCM GPIO 18, LED PWM control
	
	private int                 pwmValue;                // actual PWM value
	private Thread              dimThread        = null; // thread used for dimming
	private int                 dimDuration      = 0;    // duration for dim up
	private double              dimTargetPercent = 0;    // target brightness in % for dim up
}
