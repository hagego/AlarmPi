package alarmpi;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.LogManager;
import java.util.logging.Logger;


import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.exception.Pi4JException;
import com.pi4j.library.pigpio.PiGpio;
import com.pi4j.plugin.pigpio.provider.gpio.digital.PiGpioDigitalInputProviderImpl;
import com.pi4j.plugin.pigpio.provider.gpio.digital.PiGpioDigitalOutputProviderImpl;
import com.pi4j.plugin.pigpio.provider.i2c.PiGpioI2CProviderImpl;
import com.pi4j.plugin.pigpio.provider.spi.PiGpioSpiProviderImpl;

/**
 * The main class for the AlarmPi daemon application.
 * Reads the configuration files, spawns a thread for the TCP server
 * and then executes the Controller endless loop in a new thread
 * 
 */
public class AlarmPi {

	public static void main(String[] args) {
		try {
			// first check for a local conf directory (as it exists in the development environment)
			String configDir = "conf/";
			File confDir = new File(configDir);
			if( confDir.exists() && confDir.isDirectory()) {
				log.info("Found local conf directory. Will use this for configuration files");
			}
			else {
				configDir = "/etc/alarmpi/";
				confDir = new File(configDir);
				if( confDir.exists() && confDir.isDirectory()) {
					log.info("Found conf directory "+configDir+". Will use this for configuration files");
				}
				else {
					log.severe("Unable to find a valid configuration directory. Exiting...");
					
					return;
				}
			}
			
			// can't get setting of the format to work ;-( 
			System.setProperty( "java.util.logging.SimpleFormatter.format","%4$s: %5$s");
			
			// force reading of logger / handler configuration file
			String loggingConfigFile = configDir+"alarmpi.logging";
			System.setProperty( "java.util.logging.config.file", loggingConfigFile );
			try {
				LogManager.getLogManager().readConfiguration();
				log.info("logging configuration read from "+loggingConfigFile);
				System.out.println("logging configuration read from "+loggingConfigFile);
			}
			catch ( Exception e ) { e.printStackTrace(); }
			
			log.info("AlarmPi started successfully, now reading configuration");
			
			
			// read configuration file
			Configuration.read(configDir+"alarmpi.cfg");
			Configuration configuration = Configuration.getConfiguration();
			
			log.info("configuration read successfully");
			// touch (create) watchdog file
			if(configuration.getRunningOnRaspberry()) {
				try {
					FileWriter writer = new FileWriter(watchDogFile);
					writer.write(LocalTime.now().toString());
					writer.close();
				} catch (IOException e) {
					log.severe("Unable to update watchdog file: "+e.getMessage());
				}
			}
			
				
			// thread runtime exception handler
			Thread.UncaughtExceptionHandler handler = new Thread.UncaughtExceptionHandler() {
			    @Override
			    public void uncaughtException(Thread th, Throwable ex) {
			        log.severe("Uncaught runtime exception in thread: "+ex.getMessage());
			        log.severe("Uncaught runtime exception in thread: "+ex.getCause());
			        for(StackTraceElement element:ex.getStackTrace()) {
	        			log.severe(element.toString());
	        		}
			    }
			};
			
			// Initialize pi4j
	        try {
	        	PiGpio piGpio = PiGpio.newNativeInstance();
	        	
	        	// switch to PWM as clock source so that sound output is not distorted
	        	piGpio.gpioCfgClock(5, 0, 0);
	        	piGpio.initialise();
	        	
	        	log.fine("pigpio version: "+piGpio.gpioVersion());
	        	log.fine("pigpio HW version: "+piGpio.gpioHardwareRevisionString());
	        	
	        	pi4j = Pi4J.newContextBuilder()
	            .noAutoDetectPlatforms()
	            .noAutoDetectProviders()
	            .noAutoDetect()
	            .add(new PiGpioI2CProviderImpl(piGpio),
	            	 new PiGpioDigitalInputProviderImpl(piGpio),
	            	 new PiGpioDigitalOutputProviderImpl(piGpio),
	            	 new PiGpioSpiProviderImpl(piGpio) )
	            .build();
	        	
	            // dump some pi4j details into logfile
	            var platforms = pi4j.platforms().all();
	            for(String name:platforms.keySet()) {
	            	log.fine("PI4J Platform description: "+name+" : "+platforms.get(name).description());
	            	var providers = pi4j.providers();
	            	for(var provider:providers.getAll().keySet()) {
	            		log.fine("provider: "+provider+" : "+providers.get(provider).description());
	            	}
	            }
	            
	            log.info("PI4J context created successfully");
	        }
	        catch (Pi4JException e) {
	        	log.severe("Exception during creation of pi4j context");
	        	log.severe(e.getMessage());
	        	log.severe(e.getCause().toString());
	        }
	        catch( IllegalStateException e) {
	        	log.severe("Exception during creation of pi4j context");
	        	log.severe(e.getMessage());
	        	log.severe(e.getCause().toString());
	        }
	        catch( Throwable e) {
	        	log.severe("Exception during creation of pi4j context");
	        	log.severe(e.getMessage());
	        	log.severe(e.getCause().toString());
	        }

			// initialize sound control
			SoundControl.setPi4jContext(pi4j);
			
			// create the user thread to manage alarms and HW buttons
			final Controller controller = new Controller(pi4j);
			final Thread controllerThread = new Thread(controller);
			controllerThread.setDaemon(false);
			controllerThread.setUncaughtExceptionHandler(handler);
			controllerThread.start();
			
			// prepare threads for TCP servers
			final ExecutorService threadPool = Executors.newCachedThreadPool();
			
			if(configuration.getJsonServerPort()==null) {
				// no port specified (or set to 0)
				log.severe("No HTTP JSON server port specified - no server is started");
			}
			else {
			    try {
			    	final ServerSocket jsonServerSocket = new ServerSocket(configuration.getJsonServerPort());
					
					Thread jsonServerThread = new Thread(new TcpServer(TcpServer.Type.JSON,controller,jsonServerSocket,threadPool));
					jsonServerThread.setDaemon(true);
					jsonServerThread.setUncaughtExceptionHandler(handler);
					jsonServerThread.start();
					
				    Runtime.getRuntime().addShutdownHook( new Thread() {
						public void run() {
							log.info("shutdown hook started to shut down json server");
							try {
								jsonServerSocket.close();
								log.info("json server socket closed");
							}
							catch (IOException  e) {
								log.severe("Exception during shutdown: "+e);
							}
						}
					});
				} catch (IOException e) {
					log.severe("Unable to create server socket for json client access on port "+configuration.getJsonServerPort());
					log.severe(e.getMessage());
				}
			}
			
			// add hook to shut down server at a CTRL-C or system shutdown
			// actually copy & paste code from some forum on the web - no idea if it is working
		    Runtime.getRuntime().addShutdownHook( new Thread() {
				public void run() {
					// logging in shutdown hook does not work
					log.info("shutdown hook started to end controller thread");
					
					// write timestamp of shutdown to a /tmp file
					if(configuration.getRunningOnRaspberry()) {
						try {
							FileWriter writer = new FileWriter("/etc/alarmpi/tmp/AlarmPiShutdown.txt");
							writer.write(LocalDate.now().toString());
							writer.write(LocalTime.now().toString());
							writer.close();
						} catch (IOException e) {}
					}
					
					// switch all lights and alarms off
					controller.allOff(false);
					controllerThread.interrupt();
					threadPool.shutdownNow();
					
					if (pi4j != null) {
			            pi4j.shutdown();
			        }
				}
			});
			
			log.info("AlarmPi main is now finished");
			
			return;

		}
		catch(Throwable e) {
			log.severe("Uncaught runtime exception in main thread: "+e.getMessage());
			log.severe("Uncaught runtime exception in main thread: "+e.getCause());
			for(StackTraceElement element:e.getStackTrace()) {
    			log.severe(element.toString());
    		}
		}
	}

	// private members
	private static final Logger log = Logger.getLogger( AlarmPi.class.getName() );
	
	final static String watchDogFile       = "/var/log/alarmpi/watchdog";
	
	static Context pi4j = null;
}

