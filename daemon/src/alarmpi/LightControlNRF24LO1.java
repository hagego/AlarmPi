package alarmpi;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.logging.Logger;

public class LightControlNRF24LO1 implements LightControl {
	
	final private short CMD_ON   = 1;
	final private short CMD_OFF  = 2;

	@Override
	public void off() {
		sendCommand(CMD_OFF);
	}

	@Override
	public void off(int lightId) {
		sendCommand(CMD_OFF);
	}

	@Override
	public int getCount() {
		return 1;
	}

	@Override
	public void setBrightness(double percentage) {
		if(percentage>0) {
			sendCommand(CMD_ON);
		}
		else {
			sendCommand(CMD_OFF);
		}
	}

	@Override
	public void setBrightness(int lightId, double percentage) {
		if(percentage>0) {
			sendCommand(CMD_ON);
		}
		else {
			sendCommand(CMD_OFF);
		}
	}

	@Override
	public double getBrightness() {
		return lastCommandWasOn ? 100 : 0;
	}

	@Override
	public double getBrightness(int lightId) {
		return lastCommandWasOn ? 100 : 0;
	}

	@Override
	public void setPwm(int pwmValue) {
		if(pwmValue>0) {
			sendCommand(CMD_ON);
		}
		else {
			sendCommand(CMD_OFF);
		}
	}

	@Override
	public void setPwm(int lightId, int pwmValue) {
		if(pwmValue>0) {
			sendCommand(CMD_ON);
		}
		else {
			sendCommand(CMD_OFF);
		}
	}

	@Override
	public int getPwm() {
		return lastCommandWasOn ? 255 : 0;
	}

	@Override
	public void dimUp(double finalPercent, int seconds) {
		sendCommand(CMD_ON);
	}
	
	private void sendCommand(int command) {
		final short HEADER_LENGTH = 3;
		final short HEADER_BYTE   = 0x55;
		
		short data[] = new short[HEADER_LENGTH+1];
	    for(short i=0 ; i<HEADER_LENGTH ; i++) {
	      data[i] = HEADER_BYTE;
	    }
	    
	    switch(command) {
		    case CMD_ON:
		    	data[HEADER_LENGTH] = CMD_ON;
		    	lastCommandWasOn    = true;
		    	break;
		    case CMD_OFF:
		    	data[HEADER_LENGTH] = CMD_OFF;
		    	lastCommandWasOn    = false;
		    	break;
		    default:
		    	log.severe("Unknown command in sendCommand: "+command);
		    	return;
	    }
		
		NRF24LO1Control nRF204Control = new NRF24LO1Control();
		
		if(!nRF204Control.init()) {
			log.severe("Unable to initialize nRF24LO1 Control Object");
			return;
		}
		
		  // Defaults after init are 2.402 GHz (channel 2), 2Mbps, 0dBm
		  try {
			nRF204Control.setChannel(1);
			
			nRF204Control.setRF(NRF24LO1Control.DataRate.DataRate2Mbps, NRF24LO1Control.TransmitPower.TransmitPower0dBm);

			// send command for 10 seconds
			long start = System.nanoTime();
			while((System.nanoTime()-start)<10E9) {
		    	nRF204Control.send(data);
		    	nRF204Control.waitPacketSent();
		    	
		    	try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
				}
			}
			    
		} catch (IOException e) {
			log.severe("IO Exception while talking to nRF24LO1: "+e.getMessage());
		} 
	}

	private static final Logger   log     = Logger.getLogger( MethodHandles.lookup().lookupClass().getName() );
	
	private boolean lastCommandWasOn = false;
	
}
