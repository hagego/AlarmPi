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

	public TcpServer(Controller controller,ServerSocket serverSocket,ExecutorService threadPool) {
		this.serverSocket  = serverSocket;
		this.threadPool    = threadPool;
		this.controller    = controller;
	}

	@Override
	public void run() {
		log.info("Starting communication server on port "+serverSocket.getLocalPort());
		
		// wait for clients to connect
		while(!serverSocket.isClosed()) {
			try {
				Socket clientSocket = serverSocket.accept();
				threadPool.execute(new TcpRequestHandler(controller, clientSocket));
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
	private final ServerSocket    serverSocket;
	private final ExecutorService threadPool;
	private final Controller      controller;
}
