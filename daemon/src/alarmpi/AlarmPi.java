package alarmpi;

import java.nio.file.Paths;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * The main class for the AlarmPi daemon application.
 * Reads the configuration files, spawns a thread for the TCP server
 * and then executes the Controller loop in this thread
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
		System.setProperty( "java.util.logging.config.file", configDir+"alarmpi.logging" );
		try {
			LogManager.getLogManager().readConfiguration();
			log.info("logging configuration read");
		}
		catch ( Exception e ) { e.printStackTrace(); }
		
		log.info("AlarmPi started successfully, now reading configuration");
		
		// read configuration file
		Configuration.read(configDir+"alarmpi.cfg");
		Configuration configuration = Configuration.getConfiguration();
		
		// create the user thread to manage alarms and HW buttons
		final Controller controller = new Controller();
		
		// start TCP server to listen for external commands
		if(configuration.getPort()==0) {
			// no port specified (or set to 0)
			log.severe("No TCP server port specified - no server is started");
		}
		else {
		    try {
				final ServerSocket serverSocket  = new ServerSocket(configuration.getPort());
				final ExecutorService threadPool = Executors.newCachedThreadPool();
				
				Thread serverThread = new Thread(new TcpServer(controller,serverSocket,threadPool));
				serverThread.setDaemon(true);
				serverThread.start();
				
				Thread controllerThread = new Thread(controller);
				controllerThread.setDaemon(false);
				controllerThread.start();
				
				// add hook to shut down server at a CTRL-C
			    Runtime.getRuntime().addShutdownHook( new Thread() {
					public void run() {
						log.info("CTRL-C - shutting down");
						//SoundControl.getSoundControl().off();
						controller.getSoundControl().off();
						controller.getLightControl().off();
						threadPool.shutdownNow(); // don't accept new requests
						try {
							// wait max. 2 seconds for termination of all threads
							threadPool.awaitTermination(2L, TimeUnit.SECONDS);
							if (!serverSocket.isClosed()) {
								log.info("shutting down server");
								serverSocket.close();
							}
							
							controllerThread.interrupt();
						}
						catch (IOException | InterruptedException e) {
							log.severe("Exception during shutdown: "+e);
						}
					}
				});
			    
			} catch (IOException e) {
				log.severe("Unable to create server socket for remote client access on port "+configuration.getPort());
				log.severe(e.getMessage());
			}
		}
		
		log.info("AlarmPi main is now finished");
	}

	// private members
	private static final Logger log = Logger.getLogger( AlarmPi.class.getName() );
}
