package alarmpi;

import java.io.IOException;
import java.util.logging.Logger;

import com.pi4j.io.spi.SpiChannel;
import com.pi4j.io.spi.SpiDevice;
import com.pi4j.io.spi.SpiFactory;
import com.pi4j.io.spi.SpiMode;

public class LedStripTrial implements Runnable{
	
	SpiDevice       spi = null;
	private static final Logger log = Logger.getLogger( LedStripTrial.class.getName() );
	
	private static final int LED_COUNT = 6;
	private static final int RESET_BYTE_COUNT = 10;
	
	public LedStripTrial() {
		// setup SPI
		try {
			spi = SpiFactory.getInstance(SpiChannel.CS0,1000000,SpiMode.MODE_0);
		} catch (IOException e) {
			log.severe("Error during SPI initialize: "+e.getMessage());
		}
		
		log.info("LedStripTrial initialized");
	}
	
	public void turnOn() {
		log.fine("LedStripTrial turnOn");
		
		short data[] = new short[LED_COUNT*3];
		
		
		for(int i=0 ; i<LED_COUNT ; i++) {
			
			data[i*3 + 0] = 1;
			data[i*3 + 1] = 0;
			data[i*3 + 2] = 0;
		}
				
		try {
			spi.write(data,0,data.length);
		} catch (IOException e) {
			log.severe("Error during SPI write: "+e.getMessage());
		}
	}
	
	public void turnOff() {
		log.fine("LedStripTrial turnOff");
		
		short data[] = new short[LED_COUNT*3];
		
		for(int i=0 ; i<LED_COUNT ; i++) {
			
			data[i*3 + 0] = 0;
			data[i*3 + 1] = 0;
			data[i*3 + 2] = 0;
		}
				
		try {
			spi.write(data,0,data.length);
		} catch (IOException e) {
			log.severe("Error during SPI write: "+e.getMessage());
		}
	}

	@Override
	public void run() {
		while(true) {
			turnOn();
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			turnOff();
			
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
}
