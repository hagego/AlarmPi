package alarmpi;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.logging.Logger;

import com.pi4j.context.Context;

public class LightControlNRF24LO1 extends LightControl {

	final private short CMD_ON  = 1;
	final private short CMD_OFF = 2;

	/**
	 * constructor
	 * @param id unique light ID
	 */
	LightControlNRF24LO1(int id,String name, Context pi4j) {
		super(id,name);
		
		nRF204Control    = new NRF24LO1Control(pi4j);
	}
	
	@Override
	public void setOff() {
		sendCommand(CMD_OFF);
	}

	@Override
	public void setBrightness(double percentage) {
		if (percentage > 0) {
			sendCommand(CMD_ON);
		} else {
			sendCommand(CMD_OFF);
		}
	}

	@Override
	public double getBrightness() {
		return lastCommandWasOn ? 100 : 0;
	}

	@Override
	public void setPwm(int pwmValue) {
		if (pwmValue > 0) {
			sendCommand(CMD_ON);
		} else {
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
		final short HEADER_BYTE = 0x55;

		short data[] = new short[HEADER_LENGTH + 1];
		for (short i = 0; i < HEADER_LENGTH; i++) {
			data[i] = HEADER_BYTE;
		}

		switch (command) {
		case CMD_ON:
			data[HEADER_LENGTH] = CMD_ON;
			lastCommandWasOn = true;
			break;
		case CMD_OFF:
			data[HEADER_LENGTH] = CMD_OFF;
			lastCommandWasOn = false;
			break;
		default:
			log.severe("Unknown command in sendCommand: " + command);
			return;
		}

		if (!nRF204Control.init()) {
			log.severe("Unable to initialize nRF24LO1 Control Object");
			return;
		}
		
		new Thread(new Runnable() {
			
			@Override
			public void run() {
				// Defaults after init are 2.402 GHz (channel 2), 2Mbps, 0dBm
				try {
					nRF204Control.setChannel(1);

					nRF204Control.setRF(NRF24LO1Control.DataRate.DataRate2Mbps,
							NRF24LO1Control.TransmitPower.TransmitPower0dBm);

					// send command for 10 seconds
					long start = System.nanoTime();
					while ((System.nanoTime() - start) < 10E9) {
						nRF204Control.send(data);
						nRF204Control.waitPacketSent();

						try {
							Thread.sleep(10);
						} catch (InterruptedException e) {
						}
					}

				} catch (IOException e) {
					log.severe("IO Exception while talking to nRF24LO1: " + e.getMessage());
				}
				finally {
					// back to idle again
					try {
						nRF204Control.setModeIdle();
					}
					catch(IOException e1) {}
				}
			}
		}).start();
	}

	private static final Logger log = Logger.getLogger(MethodHandles.lookup().lookupClass().getName());

	private NRF24LO1Control nRF204Control    = null;
	private boolean         lastCommandWasOn = false;

}
