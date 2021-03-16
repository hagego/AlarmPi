package alarmpi;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.logging.Logger;

import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.PinState;
import com.pi4j.io.gpio.RaspiPin;
import com.pi4j.io.spi.SpiChannel;
import com.pi4j.io.spi.SpiDevice;
import com.pi4j.io.spi.SpiFactory;


/**
 * class to control the nRF204LO1 chip via SPI interface.
 * This is basically a Java/PI4J clone of the  RadioHead NRF24 library
 * https://www.airspayce.com/mikem/arduino/RadioHead/classRH__NRF24.html
 * However, only the TX part is covered
 *
 */
@SuppressWarnings("unused")
public class NRF24LO1Control {
	
	//
	// private members
	//
	private static final Logger   log     = Logger.getLogger( MethodHandles.lookup().lookupClass().getName() );
	
	private final GpioPinDigitalOutput gpioOutCE;         // nRF204 chip enable
	private       SpiDevice            spiDevice;         // SPI object
	
	private       boolean              isReady;           // indicates if object can be used or not
	
	/**
	 * Defines convenient values for setting data rates in setRF()
	 */
    enum DataRate
    {
    	DataRate1Mbps,   ///< 1 Mbps
    	DataRate2Mbps,   ///< 2 Mbps
    	DataRate250kbps  ///< 250 kbps
    };
    
    /**
     * Convenient values for setting transmitter power in setRF()
     * These are designed to agree with the values for RF_PWR in RH_NRF24_REG_06_RF_SETUP
     * To be passed to setRF();
     */
    enum TransmitPower
    {
    	// Add 20dBm for nRF24L01p with PA and LNA modules
    	TransmitPowerm18dBm,        ///< On nRF24, -18 dBm
    	TransmitPowerm12dBm,        ///< On nRF24, -12 dBm
    	TransmitPowerm6dBm,         ///< On nRF24, -6 dBm
    	TransmitPower0dBm,          ///< On nRF24, 0 dBm
    };
	
	/**
	 * constructor
	 */
	public NRF24LO1Control() {
		log.fine("Instantiating nRF204 controller");
		
		GpioController gpioController = null;
		
		if(Configuration.getConfiguration().getRunningOnRaspberry()) {
			gpioController  = GpioFactory.getInstance();
		}
		if(gpioController!=null) {
			gpioOutCE = gpioController.provisionDigitalOutputPin(RaspiPin.GPIO_21, "nRF204CE", PinState.LOW);
		}
		else {
			log.severe("Unable to instantiate GPIO to control nRF204 CE");
			gpioOutCE = null;
		}
		
		if(Configuration.getConfiguration().getRunningOnRaspberry()) {
			try {
				spiDevice = SpiFactory.getInstance(SpiChannel.CS0);
			} catch (IOException e) {
				log.severe("Error during SPI initialize: "+e.getMessage());
				spiDevice = null;
			}
		}
		
		if(gpioOutCE!=null && spiDevice!=null) {
			log.config("nRF204 controller successfully instantiated");
			isReady = true;
		}
		else {
			log.severe("nRF204 controller could not be instantiated");
			isReady = false;
		}
	}
	

	/**
	 * initializes the NRF24 device
	 * @return true on success
	 *         false in case of any error
	 */
	boolean init() {
		log.fine("Initializing nRF24 controller");
		if(isReady) {
			gpioOutCE.setState(PinState.LOW);
			
			
		    // Clear interrupts
		    try {
				spiWriteRegister(RH_NRF24_REG_07_STATUS, RH_NRF24_RX_DR | RH_NRF24_TX_DS | RH_NRF24_MAX_RT);
			
			    // Enable dynamic payload length on all pipes
			    spiWriteRegister(RH_NRF24_REG_1C_DYNPD, RH_NRF24_DPL_ALL);
			    // Enable dynamic payload length, disable payload-with-ack, enable noack
			    spiWriteRegister(RH_NRF24_REG_1D_FEATURE, RH_NRF24_EN_DPL | RH_NRF24_EN_DYN_ACK);
			    // Test if there is actually a device connected and responding
			    // CAUTION: RFM73 and version 2.0 silicon may require ACTIVATE
			    short readResult = spiReadRegister(RH_NRF24_REG_1D_FEATURE);
			    if (readResult != (RH_NRF24_EN_DPL | RH_NRF24_EN_DYN_ACK))
			    { 
			    	spiWrite(RH_NRF24_COMMAND_ACTIVATE, 0x73);
			        // Enable dynamic payload length, disable payload-with-ack, enable noack
			        spiWriteRegister(RH_NRF24_REG_1D_FEATURE, RH_NRF24_EN_DPL | RH_NRF24_EN_DYN_ACK);
			        readResult = spiReadRegister(RH_NRF24_REG_1D_FEATURE);
			        if (readResult != (RH_NRF24_EN_DPL | RH_NRF24_EN_DYN_ACK)) {
			        	log.severe("Unable to initialize nRF24 device. Unexpected read result from REG_1D_FEATURE: "+readResult);
			        	
			        	isReady = false;
			            return isReady;
			        }
			    }
			    
			    // Make sure we are powered down
			    setModeIdle();
	
			    // Flush FIFOs
			    flushTx();
			    flushRx();
	
			    setChannel(2); // The default, in case it was set by another app without powering down
			    setRF(DataRate.DataRate2Mbps, TransmitPower.TransmitPower0dBm);
	
			    log.config("nRF204 controller successfully initialized");
			    isReady = true;
			    return isReady;
		    } catch (IOException e) {
		    	isReady = false;
		    	log.severe("nRF204 controller could not be initialized: "+e.getMessage());
		    	
		    	return isReady;
			}
		}
		
		log.severe("nRF204 is not ready for init()");
		return isReady;
	}
	
	/**
	 * Sets the radio in power down mode, with the configuration set to the last value from setOpMode().
	 * Sets chip enable to LOW.
	 * @throws IOException 
	 */
	void setModeIdle() throws IOException
	{
		spiWriteRegister(RH_NRF24_REG_00_CONFIG, configuration);
		gpioOutCE.setState(PinState.LOW);
	}
	
	/**
	 * Sets the radio into TX mode
	 * @throws IOException 
	 */
	void setModeTx() throws IOException
	{
		// Its the CE rising edge that puts us into TX mode
		// CE staying high makes us go to standby-II when the packet is sent
		gpioOutCE.setState(PinState.LOW);
		
		// Ensure DS is not set
		spiWriteRegister(RH_NRF24_REG_07_STATUS, RH_NRF24_TX_DS | RH_NRF24_MAX_RT);
		spiWriteRegister(RH_NRF24_REG_00_CONFIG, configuration | RH_NRF24_PWR_UP);
		
		gpioOutCE.setState(PinState.HIGH);
	}
	

	/**
	 * Sets the transmit and receive channel number. The frequency used is (2400 + channel) MHz
	 * @param channel
	 * @throws IOException 
	 */
	void setChannel(int channel) throws IOException
	{
	    spiWriteRegister(RH_NRF24_REG_05_RF_CH, channel & RH_NRF24_RF_CH);
	}
	
    /// 
    /// \param [in] data_rate The data rate to use for all packets transmitted and received. One of RH_NRF24::DataRate.
    /// \param [in] power Transmitter power. One of RH_NRF24::TransmitPower.
    /// \return true on success
	/**
	 * Sets the data rate and transmitter power to use. 
	 * @param data_rate
	 * @param power
	 * @throws IOException 
	 */
    void setRF(DataRate data_rate, TransmitPower power) throws IOException {
        int value;
        switch(power) {
        	case TransmitPowerm18dBm:
        		value = 0;
        		break;
        	case TransmitPowerm12dBm:
        		value = 2;
        		break;
        	case TransmitPowerm6dBm:
        		value = 4;
        		break;
        	case TransmitPower0dBm:
        		value = 6;
        		break;
        	default:
        		log.severe("Unknown power level in setRF: "+power);
        		isReady = false;
        		value = 0;
        }
        value &= RH_NRF24_PWR;
        
        // Ugly mapping of data rates to noncontiguous 2 bits:
        if (data_rate == DataRate.DataRate250kbps) {
        	value |= RH_NRF24_RF_DR_LOW;
        } else if (data_rate == DataRate.DataRate2Mbps) {
        	value |= RH_NRF24_RF_DR_HIGH;
        }
        // else DataRate1Mbps, 00

        // RFM73 needs this:
        value |= RH_NRF24_LNA_HCURR;
        
        spiWriteRegister(RH_NRF24_REG_06_RF_SETUP, value);
    }
	
    /**
     * transmits data
     * @param data data (bytes) to send
     * @throws IOException
     */
    void send(short data[]) throws IOException {
        if (data.length > RH_NRF24_MAX_MESSAGE_LEN) {
        	log.severe("data buffer too large");
        	throw new IOException("data buffer too large");
        }

        // Set up the headers
        final short RH_BROADCAST_ADDRESS = 0xff;
        
        final short txHeaderTo    = RH_BROADCAST_ADDRESS;
        final short txHeaderFrom  = RH_BROADCAST_ADDRESS;
        final short txHeaderId    = 0;
        final short txHeaderFlags = 0;
        
        short buffer[] = new short[data.length+RH_NRF24_HEADER_LEN];
        
        buffer[0] = txHeaderTo;
        buffer[1] = txHeaderFrom;
        buffer[2] = txHeaderId;
        buffer[3] = txHeaderFlags;
        
        for(int b=0 ; b<data.length ; b++) {
        	buffer[RH_NRF24_HEADER_LEN+b] = data[b];
        }
        spiBurstWrite(RH_NRF24_COMMAND_W_TX_PAYLOAD_NOACK, buffer);
        setModeTx();
        // Radio will return to Standby II mode after transmission is complete
    }

    /**
     * waits until the data packet sent with sendData has been transmitted
     * @return true if data has been sent successfully, otherwise false
     * @throws IOException
     */
    boolean waitPacketSent() throws IOException
    {
        // Wait for either the Data Sent or Max ReTries flag, signalling the 
        // end of transmission
        // We dont actually use auto-ack, so prob dont expect to see RH_NRF24_MAX_RT
    	short status;
        long start = System.nanoTime();
        while (((status = statusRead()) & (RH_NRF24_TX_DS | RH_NRF24_MAX_RT))==0)
        {
	    	if ((System.nanoTime() - start) > 250000000) // 250ms
	    	{
	    		throw new IOException("timeout while waiting for data to be sent");
	    	}
        }

        // Must clear RH_NRF24_MAX_RT if it is set, else no further comm
        if ((status & RH_NRF24_MAX_RT)>0) {
        	flushTx();
    	}
        setModeIdle();
        spiWriteRegister(RH_NRF24_REG_07_STATUS, RH_NRF24_TX_DS | RH_NRF24_MAX_RT);
        // Return true if data sent, false if MAX_RT
        return (status & RH_NRF24_TX_DS) > 0;
    }
	
	private void flushTx() throws IOException
	{
	    spiCommand(RH_NRF24_COMMAND_FLUSH_TX);
	}

	private void flushRx() throws IOException
	{
	    spiCommand(RH_NRF24_COMMAND_FLUSH_RX);
	}
	

	private short statusRead() throws IOException
	{
	    // status is a side-effect of NOP, faster than reading reg 07
	    return spiCommand(RH_NRF24_COMMAND_NOP); 
	}
	
	/**
	 * writes a single byte (command
	 * @param command command to write
	 * @return status byte
	 * @throws IOException 
	 */
	private short spiCommand(short command) throws IOException {
		short data[] = new short[1];
		data[0] = command;
		short status[] = null;
		
		status = spiDevice.write(data);
		
		if(status!=null && status.length==1) {
			return status[0];
		}
		else {
			throw new IOException("invalid response from spiCommand");
		}
	}
	
	/**
	 * writes a register thru a command
	 * @param reg register address
	 * @param val register value
	 * @throws IOException 
	 */
	private void spiWriteRegister(short reg,int val) throws IOException {
		spiWrite((short)((reg & RH_NRF24_REGISTER_MASK) | RH_NRF24_COMMAND_W_REGISTER), val);
	}
	
	/**
	 * writes a register value (sends 2 bytes over SPI)
	 * @param reg register address
	 * @param val register value
	 * @throws IOException 
	 */
	private void spiWrite(short reg,int val) throws IOException {
		short data[] = new short[2];
		data[0] = reg;
		data[1] = (short)val;
		
		spiDevice.write(data);
	}
	
	private void spiBurstWrite(short reg,short val[]) throws IOException {
		short data[] = new short[val.length+1];
		data[0] = reg;
		for(int b=0 ; b<val.length ; b++) {
			data[b+1] = val[b];
		}
		
		spiDevice.write(data);
	}

	/**
	 * reads a register thru a command
	 * @param reg register address
	 * @return    register value
	 * @throws IOException 
	 */
	private short spiReadRegister(short reg) throws IOException {
		return spiRead((short)((reg & RH_NRF24_REGISTER_MASK) | RH_NRF24_COMMAND_R_REGISTER));
	}
	
	private short spiRead(short reg) throws IOException {
		short result[] = null;

		short data[] = new short[2];
		data[0] = reg;
		data[1] = (short)0; // shift-in data is ignored
		
		result = spiDevice.write(data);
		
		if(result==null || result.length!=2) {
			log.severe("Invalid response during spiRead");
			throw new IOException("Invalid response during spiRead");
		}
		else {
			return result[1];
		}
	}
	
	
	/**
	 * definition of constants for nRF204
	 */
	
	// This is the maximum number of bytes that can be carried by the nRF24.
	// We use some for headers, keeping fewer for RadioHead messages
	private final short RH_NRF24_MAX_PAYLOAD_LEN = 32;

	// The length of the headers we add.
	// The headers are inside the nRF24 payload
	private final short RH_NRF24_HEADER_LEN = 4;

	// This is the maximum RadioHead user message length that can be supported by this library. Limited by
	// the supported message lengths in the nRF24
	private final short RH_NRF24_MAX_MESSAGE_LEN = RH_NRF24_MAX_PAYLOAD_LEN-RH_NRF24_HEADER_LEN;
	
	// SPI Command names
	private final short RH_NRF24_COMMAND_R_REGISTER                        = 0x00;
	private final short RH_NRF24_COMMAND_W_REGISTER                        = 0x20;
	private final short RH_NRF24_COMMAND_ACTIVATE                          = 0x50; // only on RFM73 ?
	private final short RH_NRF24_COMMAND_R_RX_PAYLOAD                      = 0x61;
	private final short RH_NRF24_COMMAND_W_TX_PAYLOAD                      = 0xa0;
	private final short RH_NRF24_COMMAND_FLUSH_TX                          = 0xe1;
	private final short RH_NRF24_COMMAND_FLUSH_RX                          = 0xe2;
	private final short RH_NRF24_COMMAND_REUSE_TX_PL                       = 0xe3;
	private final short RH_NRF24_COMMAND_R_RX_PL_WID                       = 0x60;
	//private final short RH_NRF24_COMMAND_W_ACK_PAYLOAD(pipe)               (= 0xa8|(pipe&= 0x7))
	private final short RH_NRF24_COMMAND_W_TX_PAYLOAD_NOACK                = 0xb0;
	private final short RH_NRF24_COMMAND_NOP                               = 0xff;
	
	// Register names
	private final short RH_NRF24_REGISTER_MASK                            = 0x1f;
	private final short RH_NRF24_REG_00_CONFIG                            = 0x00;
	private final short RH_NRF24_REG_01_EN_AA                             = 0x01;
	private final short RH_NRF24_REG_02_EN_RXADDR                         = 0x02;
	private final short RH_NRF24_REG_03_SETUP_AW                          = 0x03;
	private final short RH_NRF24_REG_04_SETUP_RETR                        = 0x04;
	private final short RH_NRF24_REG_05_RF_CH                             = 0x05;
	private final short RH_NRF24_REG_06_RF_SETUP                          = 0x06;
	private final short RH_NRF24_REG_07_STATUS                            = 0x07;
	private final short RH_NRF24_REG_08_OBSERVE_TX                        = 0x08;
	private final short RH_NRF24_REG_09_RPD                               = 0x09;
	private final short RH_NRF24_REG_0A_RX_ADDR_P0                        = 0x0a;
	private final short RH_NRF24_REG_0B_RX_ADDR_P1                        = 0x0b;
	private final short RH_NRF24_REG_0C_RX_ADDR_P2                        = 0x0c;
	private final short RH_NRF24_REG_0D_RX_ADDR_P3                        = 0x0d;
	private final short RH_NRF24_REG_0E_RX_ADDR_P4                        = 0x0e;
	private final short RH_NRF24_REG_0F_RX_ADDR_P5                        = 0x0f;
	private final short RH_NRF24_REG_10_TX_ADDR                           = 0x10;
	private final short RH_NRF24_REG_11_RX_PW_P0                          = 0x11;
	private final short RH_NRF24_REG_12_RX_PW_P1                          = 0x12;
	private final short RH_NRF24_REG_13_RX_PW_P2                          = 0x13;
	private final short RH_NRF24_REG_14_RX_PW_P3                          = 0x14;
	private final short RH_NRF24_REG_15_RX_PW_P4                          = 0x15;
	private final short RH_NRF24_REG_16_RX_PW_P5                          = 0x16;
	private final short RH_NRF24_REG_17_FIFO_STATUS                       = 0x17;
	private final short RH_NRF24_REG_1C_DYNPD                             = 0x1c;
	private final short RH_NRF24_REG_1D_FEATURE                           = 0x1d;
	
	// These register masks etc are named wherever possible
	// corresponding to the bit and field names in the nRF24L01 Product Specification
	// RH_NRF24_REG_00_CONFIG                             = 0x00
	private final short RH_NRF24_MASK_RX_DR                                = 0x40;
	private final short RH_NRF24_MASK_TX_DS                                = 0x20;
	private final short RH_NRF24_MASK_MAX_RT                               = 0x10;
	private final short RH_NRF24_EN_CRC                                    = 0x08;
	private final short RH_NRF24_CRCO                                      = 0x04;
	private final short RH_NRF24_PWR_UP                                    = 0x02;
	private final short RH_NRF24_PRIM_RX                                   = 0x01;

	// RH_NRF24_REG_01_EN_AA                              = 0x01
	private final short RH_NRF24_ENAA_P5                                   = 0x20;
	private final short RH_NRF24_ENAA_P4                                   = 0x10;
	private final short RH_NRF24_ENAA_P3                                   = 0x08;
	private final short RH_NRF24_ENAA_P2                                   = 0x04;
	private final short RH_NRF24_ENAA_P1                                   = 0x02;
	private final short RH_NRF24_ENAA_P0                                   = 0x01;

	// RH_NRF24_REG_02_EN_RXADDR                          = 0x02
	private final short RH_NRF24_ERX_P5                                    = 0x20;
	private final short RH_NRF24_ERX_P4                                    = 0x10;
	private final short RH_NRF24_ERX_P3                                    = 0x08;
	private final short RH_NRF24_ERX_P2                                    = 0x04;
	private final short RH_NRF24_ERX_P1                                    = 0x02;
	private final short RH_NRF24_ERX_P0                                    = 0x01;

	// RH_NRF24_REG_03_SETUP_AW                           = 0x03
	private final short RH_NRF24_AW_3_shortS                                = 0x01;
	private final short RH_NRF24_AW_4_shortS                                = 0x02;
	private final short RH_NRF24_AW_5_shortS                                = 0x03;

	// RH_NRF24_REG_04_SETUP_RETR                         = 0x04
	private final short RH_NRF24_ARD                                       = 0xf0;
	private final short RH_NRF24_ARC                                       = 0x0f;

	// RH_NRF24_REG_05_RF_CH                              = 0x05
	private final short RH_NRF24_RF_CH                                     = 0x7f;

	// RH_NRF24_REG_06_RF_SETUP                           = 0x06
	private final short RH_NRF24_CONT_WAVE                                 = 0x80;
	private final short RH_NRF24_RF_DR_LOW                                 = 0x20;
	private final short RH_NRF24_PLL_LOCK                                  = 0x10;
	private final short RH_NRF24_RF_DR_HIGH                                = 0x08;
	private final short RH_NRF24_PWR                                       = 0x06;
	private final short RH_NRF24_PWR_m18dBm                                = 0x00;
	private final short RH_NRF24_PWR_m12dBm                                = 0x02;
	private final short RH_NRF24_PWR_m6dBm                                 = 0x04;
	private final short RH_NRF24_PWR_0dBm                                  = 0x06;
	private final short RH_NRF24_LNA_HCURR                                 = 0x01;

	// RH_NRF24_REG_07_STATUS                             = 0x07
	private final short RH_NRF24_RX_DR                                     = 0x40;
	private final short RH_NRF24_TX_DS                                     = 0x20;
	private final short RH_NRF24_MAX_RT                                    = 0x10;
	private final short RH_NRF24_RX_P_NO                                   = 0x0e;
	private final short RH_NRF24_STATUS_TX_FULL                            = 0x01;

	// RH_NRF24_REG_08_OBSERVE_TX                         = 0x08
	private final short RH_NRF24_PLOS_CNT                                  = 0xf0;
	private final short RH_NRF24_ARC_CNT                                   = 0x0f;

	// RH_NRF24_REG_09_RPD                                = 0x09
	private final short RH_NRF24_RPD                                       = 0x01;

	// RH_NRF24_REG_17_FIFO_STATUS                        = 0x17
	private final short RH_NRF24_TX_REUSE                                  = 0x40;
	private final short RH_NRF24_TX_FULL                                   = 0x20;
	private final short RH_NRF24_TX_EMPTY                                  = 0x10;
	private final short RH_NRF24_RX_FULL                                   = 0x02;
	private final short RH_NRF24_RX_EMPTY                                  = 0x01;

	// RH_NRF24_REG_1C_DYNPD                              = 0x1c
	private final short RH_NRF24_DPL_ALL                                   = 0x3f;
	private final short RH_NRF24_DPL_P5                                    = 0x20;
	private final short RH_NRF24_DPL_P4                                    = 0x10;
	private final short RH_NRF24_DPL_P3                                    = 0x08;
	private final short RH_NRF24_DPL_P2                                    = 0x04;
	private final short RH_NRF24_DPL_P1                                    = 0x02;
	private final short RH_NRF24_DPL_P0                                    = 0x01;

	// RH_NRF24_REG_1D_FEATURE                            = 0x1d
	private final short RH_NRF24_EN_DPL                                    = 0x04;
	private final short RH_NRF24_EN_ACK_PAY                                = 0x02;
	private final short RH_NRF24_EN_DYN_ACK                                = 0x01;
	
	private final short configuration = (short)(RH_NRF24_EN_CRC | RH_NRF24_CRCO); // Default: 2 byte CRC enabled
}
