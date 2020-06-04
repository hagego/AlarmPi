package alarmpi;

import java.nio.file.Paths;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * The main class for the AlarmPi daemon application.
 * Reads the configuration files, spawns a thread for the TCP server
 * and then executes the Controller endless loop in a new thread
 * 
 */
public class AlarmPi {

	public static void main(String[] args) {
		// read configuration file.
		String configDir = "";
		if(System.getProperty("os.name").toLowerCase(Locale.ENGLISH).startsWith("windows")) {
			// on windows (used for development/debugging), expect config files in conf subdirectory of Eclipse project
			configDir = Paths.get(".").toAbsolutePath().normalize().toString()+"\\conf\\";
			log.info("AlarmPi started, running on Windows");
		}
		else {
			// on Linux/Raspberry, expect configuration data in /etc/alarmpi
			configDir = "/etc/alarmpi/";
			log.info("AlarmPi started, running on Linux/Raspberry");
		}
		log.info("config directory="+configDir);
		
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
		
		// add sound duration and build playlists
		Configuration.getConfiguration().processSoundList();
		
		// create the user thread to manage alarms and HW buttons
		final Controller controller = new Controller();
		final Thread controllerThread = new Thread(controller);
		controllerThread.setDaemon(false);
		controllerThread.start();
		
		// prepare threads for TCP servers
		final ExecutorService threadPool = Executors.newCachedThreadPool();
		
		// start TCP server to listen for external commands
		if(configuration.getPort()==0) {
			// no port specified (or set to 0)
			log.severe("No TCP cmd server port specified - no server is started");
		}
		else {
		    try {
		    	final ServerSocket cmdServerSocket  = new ServerSocket(configuration.getPort());
				
				Thread cmdServerThread = new Thread(new TcpServer(TcpServer.Type.CMD,controller,cmdServerSocket,threadPool));
				cmdServerThread.setDaemon(true);
				cmdServerThread.start();
				
			    Runtime.getRuntime().addShutdownHook( new Thread() {
					public void run() {
						log.info("shutdown hook started to shut down command server");
						
						try {
							cmdServerSocket.close();
							log.info("command server socket closed");
						}
						catch (IOException  e) {
							log.severe("Exception during shutdown: "+e);
						}
					}
				});
			} catch (IOException e) {
				log.severe("Unable to create server socket for remote client access on port "+configuration.getPort());
				log.severe(e.getMessage());
			}
		}
		
		if(configuration.getJsonServerPort()==0) {
			// no port specified (or set to 0)
			log.severe("No HTTP JSON server port specified - no server is started");
		}
		else {
		    try {
		    	final ServerSocket jsonServerSocket = new ServerSocket(configuration.getJsonServerPort());
				
				Thread jsonServerThread = new Thread(new TcpServer(TcpServer.Type.JSON,controller,jsonServerSocket,threadPool));
				jsonServerThread.setDaemon(true);
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
				log.info("shutdown hook started to end controller thread");
				
				// switch all lights and alarms off
				controller.allOff(false);
				controllerThread.interrupt();
				threadPool.shutdownNow();
			}
		});
		
		log.info("AlarmPi main is now finished");
	}

	// private members
	private static final Logger log = Logger.getLogger( AlarmPi.class.getName() );
}
