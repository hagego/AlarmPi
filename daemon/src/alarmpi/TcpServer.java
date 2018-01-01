package alarmpi;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

/**
 * TCP server class which accepts connections from clients and creates a TcpRequestHandler
 * for each client.
 */
public class TcpServer implements Runnable {

	public TcpServer(Type type,Controller controller,ServerSocket serverSocket,ExecutorService threadPool) {
		this.type          = type;
		this.serverSocket  = serverSocket;
		this.threadPool    = threadPool;
		this.controller    = controller;
	}

	@Override
	public void run() {
		log.info("Starting communication server of type "+type+" on port "+serverSocket.getLocalPort());
		
		// wait for clients to connect
		while(!serverSocket.isClosed()) {
			try {
				Socket clientSocket = serverSocket.accept();
				switch(type) {
					case CMD: threadPool.execute(new TcpRequestHandler(controller, clientSocket));
						break;
					case JSON: threadPool.execute(new JsonRequestHandler(controller, clientSocket));
						break;
					default: log.severe("Unknown server type: "+type);
				}
				
			} catch (IOException e) {
				log.severe("IO error during TCP client connect");
				log.severe(e.getMessage());
			}
		}
		log.warning("Shutting down TCP Server");
	}
	
	//
	// private data members
	//
	private static final Logger log = Logger.getLogger( TcpServer.class.getName() );
	
	public enum Type {CMD,JSON};                          // defines protocol
	
	private final Type            type;
	private final ServerSocket    serverSocket;
	private final ExecutorService threadPool;
	private final Controller      controller;
}
