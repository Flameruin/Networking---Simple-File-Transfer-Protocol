package server;

import java.io.*;
import java.net.*;
/*
 * Basic server code
 * socket,port
 * 
 * Once starts wait for a client connection
 * each connection creates SFTP Connection which starts on a different thread
 */
public class Server {
	private ServerSocket socket;
	private static final int PORT = 115;

	Server() throws IOException{
		socket = new ServerSocket(PORT);
	}

	public void start() throws IOException{

		while (true) {
			
			Socket connectionSocket = socket.accept();		
			Thread t = new SFTPConnection(connectionSocket);
			t.start();			
		}	
	}

	public static void main(String[] args) throws IOException {
		System.out.println("Starting Server");		
		Server server = new Server();
		server.start();
	}
}
