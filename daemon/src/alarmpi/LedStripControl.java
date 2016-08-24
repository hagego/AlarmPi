package alarmpi;

import com.pi4j.io.spi.SpiChannel;
import com.pi4j.io.spi.SpiDevice;
import com.pi4j.io.spi.SpiFactory;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Class to control an LED Strip connected via SPI bus
 * Just a trial, not used by AlarmPi
 */
class LedStripControl {
	public LedStripControl() {
		executor = Executors.newFixedThreadPool(1);
	}
	
	/**
	 * execute an LED pattern
	 * @param pattern to execute
	 */
	public void executePattern(LedPattern pattern) {
		// stop active pattern (if any)
		if(activePattern!=null) {
			activePattern.stop();
		}
		
		// and execute new one
		activePattern = pattern;
		executor.execute(activePattern);
	}
	
	
	/**
	 * abstract base class to display a pattern at the LEDs
	 */
	public abstract class LedPattern implements Runnable {

		/**
		 * Constructor
		 * @param sleepInterval interval between 2 LED updates in ms
		 */
		public LedPattern(int sleepInterval) {
			this.sleepInterval = sleepInterval;
			ledCount           = 64;
			data               = new short[ledCount*3];
			
			// setup SPI
			try {
				spi = SpiFactory.getInstance(SpiChannel.CS0);
			} catch (IOException e) {
				log.severe("Error during SPI initialize: "+e.getMessage());
			}
		}

		/**
		 * sets correction factors for R,G,B
		 * @param correctionFactorR
		 * @param correctionFactorG
		 * @param correctionFactorB
		 */
		void setCorrectionFactors(double correctionFactorR,double correctionFactorG,double correctionFactorB) {
			this.correctionFactorR = correctionFactorR;
			this.correctionFactorG = correctionFactorG;
			this.correctionFactorB = correctionFactorB;
		}
		
		@Override
		public void run() {
			log.info("LedPattern thread started. SleepInterval="+sleepInterval);
			initializeLedData();
			
			boolean hasMoreUpdates;
			do {
				hasMoreUpdates = updateLedData();
				log.finest("hasMore Updated="+hasMoreUpdates);
				activateLedData();
				
				if(hasMoreUpdates && !stopFlag) {
					try
					{
						Thread.sleep( sleepInterval );
					}
					catch ( InterruptedException e ) {
					}
				}
			}
			while(hasMoreUpdates && !stopFlag);
			
			log.info("LedPattern thread finished");
		}
		
		/**
		 * sets the color for a single LED
		 * @param led led index
		 * @param r   intensity red (0-255)
		 * @param g   intensity green (0-255)
		 * @param b   intensity blue (0-255)
		 */
		protected void setColor(int led,short r,short g,short b) {
			log.finest("setColor: led="+led+" r="+r+" g="+g+" b="+b);
			data[led*3+0] = (short)(correctionFactorR*r);
			data[led*3+1] = (short)(correctionFactorB*b);
			data[led*3+2] = (short)(correctionFactorG*g);
		}
		
		/**
		 * shift the LED colors right
		 */
		protected void shiftRight() {
			short bufferR = data[(ledCount-1)*3];
			short bufferG = data[(ledCount-1)*3+1];
			short bufferB = data[(ledCount-1)*3+2];
			
			for(int led=ledCount-2 ; led>=0 ; led--) {
				data[led*3+3] = data[led*3];
				data[led*3+4] = data[led*3+1];
				data[led*3+5] = data[led*3+2];
			}
			
			data[0] = bufferR;
			data[1] = bufferG;
			data[2] = bufferB;
		}
		
		/**
		 * increases each color value and wraps around to zero
		 */
		protected void increaseAndWrapAround() {
			for(int led=0 ; led<ledCount ; led++) {
				for(int color=0 ; color<3 ; color++) {
					data[led*3+color]++;
					if(data[led*3+color]>255) {
						data[led*3+color] = 0;
					}
				}
			}
		}
		
		protected void activateLedData() {
			short[] copy = data.clone();
			
			if(spi!=null) {
				try {
					spi.write(copy);
				} catch (IOException e) {
					log.severe("SPI write error: "+e.getMessage());
				}
			}
		}
		
		/**
		 * sets the initial LED colors
		 */
		abstract void initializeLedData();
			
	 
		/**
		 * sets LED colors for the next update
		 * @return true if there are more updates after this one, false otherwise
		 */
		abstract boolean updateLedData();
		
		/**
		 * stops execution of this pattern immediately
		 */
		public void stop() {
			stopFlag = true;
		}
		
		
		// private members
		protected int   ledCount;           // number of LEDs in strip  
		private short[] data;               // SPI data for the LED strtip
		private int     sleepInterval;      // update interval in ms
		private boolean stopFlag = false;   // flag used to indicate stop condition
		
		private double correctionFactorR = 1.0;   // correction factor red
		private double correctionFactorG = 1.0;   // correction factor green
		private double correctionFactorB = 1.0;   // correction factor blue
	};
	
	/**
	 * LED pattern: turn all off
	 */
	class LedPatternOff extends LedPattern {
		LedPatternOff() {
			super(0);
		}
		
		void initializeLedData() {
			// set all LEDs to zero
			for(int led=0 ; led<ledCount ; led++) {
				setColor(led, (short)0, (short)0, (short)0);
			}
		}
		
		boolean updateLedData() {
			return false;
		}
	}
	
	/**
	 * LED pattern: slowly increases brightness (all white)
	 */
	class LedPatternDimUp extends LedPattern {
		/**
		 * Constructor
		 * @param duration   duration of pattern in seconds
		 * @param finalValue max brightness value at the end
		 */
		LedPatternDimUp(int duration,short finalValue) {
			super(1000*duration/finalValue);
			this.finalValue = finalValue;
			nextValue       = 0;
			
			log.fine("LedPatternDimUp: duration="+duration+" finalValue="+finalValue);
		}
		
		void initializeLedData() {
			// set all LEDs to zero
			for(int led=0 ; led<ledCount ; led++) {
				setColor(led, (short)0, (short)0, (short)0);
			}
		}
		
		boolean updateLedData() {
			log.fine("LedPatternDimUp: updateLedData to "+nextValue);
			for(int led=0 ; led<ledCount ; led++) {
				setColor(led, (short)nextValue, (short)nextValue, (short)nextValue);
			}
			
			nextValue++;
			if(nextValue<=finalValue) {
				return true;
			}
			else {
				return false;
			}
		}
		
		private short finalValue; // final brightness value
		private short nextValue;  // next value to set
	}
	
	/**
	 * LED pattern: rainbow
	 */
	abstract class LedPatternRainbowBase extends LedPattern {

		public LedPatternRainbowBase(int sleepInterval,double brightness) {
			super(sleepInterval);
			
			this.brightness = brightness;
			if(this.brightness>1.0) {
				this.brightness = 1.0;
			}
		}
		
		void initializeLedData() {
			// rainbow consists of 6 sections with linear color changes in each section
			final int sectionCount    = 6;
			final int ledsPerSection  = ledCount/sectionCount;
			final int extraLedCount   = ledCount%sectionCount;
			final short valueMax      = (short)(255.0*brightness);
			
			int ledIndex = 0;
			for(int section=0 ; section<sectionCount ; section++) {
				int ledCountThisSection = section<extraLedCount ? ledsPerSection+1 : ledsPerSection;
				
				for(int led=0 ; led<ledCountThisSection ; led++) {
					// value for rising/falling colors
					short valueRising  = (short)(brightness*255*led/(ledCountThisSection-1));
					short valueFalling = (short)(255-brightness*255*led/(ledCountThisSection-1));
					
					if(section==0) {
						setColor(ledIndex,valueMax,valueRising,(short)0);
					}
					if(section==1) {
						setColor(ledIndex,valueFalling,valueMax,(short)0);
					}
					if(section==2) {
						setColor(ledIndex,(short)0,valueMax,valueRising);
					}
					if(section==3) {
						setColor(ledIndex,(short)0,valueFalling,valueMax);
					}
					if(section==4) {
						setColor(ledIndex,valueRising,(short)(0),valueMax);
					}
					if(section==5) {
						setColor(ledIndex,valueMax,(short)(0),valueFalling);
					}
					
					ledIndex++;
				}
			}
		}

		// private data
		double brightness;
	}
	
	class LedPatternRainbow1 extends LedPatternRainbowBase {
		
		public LedPatternRainbow1(int sleepInterval,double brightness) {
			super(sleepInterval,brightness);
		}
		
		@Override
		boolean updateLedData() {
			shiftRight();
			return true;
		}
	}
	
	class LedPatternRainbow2 extends LedPatternRainbowBase {
		
		public LedPatternRainbow2(int sleepInterval,double brightness) {
			super(sleepInterval,brightness);
		}
		
		@Override
		boolean updateLedData() {
			increaseAndWrapAround();
			return true;
		}
	}
	
	// private members of LedStripControl
	
	private static final Logger log = Logger.getLogger( LedStripControl.class.getName() );
	
	ExecutorService executor;
	SpiDevice       spi = null;
	LedPattern      activePattern = null;
}
