package client;

import java.io.*;
import java.net.*;


import java.nio.file.*;


public class Client {
	
	// \u0000 is the NULL character
	
	/*
	 * Spaces are used as delimiters (when split remove blanks from the string)
	 */
	private static final String delims ="\\s+";// /^,;\<>[|:&]+$?
	/*
	 * Was used for debug
	 * Static Address is easier faster than inputing one
	 */
	//private static final String SERVER_ADDRESS = "localhost";
	/*
	 * Port which is used by SFTP protocol
	 */
	private static final int PORT = 115;
	/*
	 * Pointing to default directory to which the client will save received files
	 * Can be changed at at programmer will
	 */
	private static final File DEFAULT_DIRECTORY = FileSystems.getDefault().getPath("RootFolder/ClientFolder").toFile().getAbsoluteFile();

	private static InetAddress serverAdress;	
	private Socket clientSocket;
	// Server output
	private DataOutputStream outServer;
	
	//Buffers to read/send input between Server & Client 	 
	private BufferedReader inServer;
	private BufferedReader inUser;

	private BufferedInputStream dataInServer;

	
	Client() throws UnknownHostException, IOException{
		
		/*
		 * Ask user to input the server address
		 * Gets it and set it to connect to the server
		 */
			System.out.println("Enter the server IP:");
			try {
				BufferedReader serverIP = new BufferedReader(new InputStreamReader(System.in));
				Client.serverAdress = InetAddress.getByName(serverIP.readLine());		
			} catch (IOException e) {
				e.printStackTrace();
			}
		//Entails the client socket to connect to server	
		this.clientSocket = new Socket(serverAdress, PORT);
		/*
		 * Buffers to get User input and Server input
		 */
		this.inUser = new BufferedReader(new InputStreamReader(System.in));
		this.inServer = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
		//Getting the outputStram for sending messages to server
		this.outServer = new DataOutputStream(clientSocket.getOutputStream());
		//Getting the input stream to get files from server
		this.dataInServer = new BufferedInputStream(clientSocket.getInputStream());
	}

	/*
	 * Starting the client to connect to server
	 * All client/server operations are done from here
	 */
	public void start() throws IOException{
		//A string read/send message from the server/client respectively
		String serverSentence;
		String clientSentence;
		
		
		/*
		 * Gets and print the server response
		 * SFTP works like so:
		 * Clients send Server Receive, Server send Client Receive
		 * That's why before we enter the while loop we must get server response that we got connected
		 */
		serverSentence = readMessage();
		System.out.println(serverSentence);
		/*
		 * A while loop where client will request commands from server
		 * Commands are:
		 * DONE -> close connection
		 * USER -> ask for user confirmation (If Admin login)
		 * ACCT -> ask for account confirmation 
		 * PASS -> ask for password confirmation
		 * (needs to confirm account and password to login)
		 * LIST (V/F) -> ask for a list of current work directory of server
		 * LIST V -> Returns verbose(more info on files) list
		 * LIST F -> Returns minimal list
		 * CDIR ->  This will change the current working directory on the server to the argument passed.
		 * KILL -> This will delete the file from the remote system
		 * RETR -> Requests that the remote system send the specified file.
		 */
		while(true) {
			/*
			 * Gets user input
			 */
			clientSentence = inUser.readLine();
		
		
			/*
			 * Commands must be 4 letters so if the input is less it's not a command
			 * So just send it and let server handle it
			 * Else we check if the command was legal
			 * 
			 */
			if(clientSentence.length()<4)sendMessage(clientSentence);
			else{
				switch(clientSentence.substring(0, 4).toUpperCase()) {
				//RETR command needs to check file size so goes to a separate function
				case "RETR":
					retrCommand(clientSentence);
					continue;
				/*
				 * DONE finish connection
				 * Maybe should have done a different function but
				 * for comfort all is here	
				 */
				case "DONE":
					sendMessage(clientSentence);
					
					//Waiting for the remote system reply
					serverSentence = readMessage();
					if (serverSentence.charAt(0) == '+') {
						System.out.println(serverSentence);
						clientSocket.close();
						
						return;
					}
				//Other messages are dealt server side	
				default:
					sendMessage(clientSentence);
					break;
				}	
			}
			//gets and print server response sentence
			serverSentence = readMessage();
			System.out.println(serverSentence);
		}
	}
	

	/*  
	 * Reads messages from server
	 */ 
	private String readMessage() {
		String sentence = "";
		int character = 0;
		
		while (true){
			try {
				character = inServer.read(); 
			} catch (IOException e) {
				e.printStackTrace();
			}
			
			// <NULL> detected, return sentence.
			if (character == 0) {
				break;
			}
			
			// Adds null to end of sentence like stated in protocol
			sentence = sentence.concat(Character.toString((char)character));
		}

		return sentence;
	}

	/* 
	 * Writes message string to send to server terminated by a <NULL> ('\u0000').
	 * */
	private void sendMessage(String sentence){
		try {
			outServer.writeBytes(sentence.concat(Character.toString('\u0000')));
		} catch (IOException e) {}
	}

	//The retr command to get file from server
	private boolean retrCommand(String sentence) throws IOException {
	
		sendMessage(sentence);
		//Puts arguments to string without delims
		String[] tokens = sentence.split(delims,2);
		String filename=null;
		
		// Check if filename exits
		if (tokens.length==2) {
			filename = tokens[1];
		}		
		//gets response from server
		String serverSentence = readMessage();
		/*
		 *  If the response was '-' we fail
		 *  So unless server fail(-) we check the file and see if we can handle it
		 *  if we can handle it we ask the server to send it
		 *  if not we ask the server to cancel
		 */
		if (serverSentence.charAt(0) != '-') {
			// Get file size
			long fileSize = Long.valueOf(serverSentence);
			// File fits, tell server to send
			long usableSpace = Files.getFileStore(DEFAULT_DIRECTORY.toPath().toRealPath()).getUsableSpace();			
			if (fileSize < usableSpace){ 
				sendMessage("SEND");
				
			} else {
				sendMessage("STOP");
				System.out.println(readMessage());  // Server replies "aborted"
				
				return false;
			}
			receiveFile(filename, fileSize);
			System.out.println(String.format("File transfererd"));
			return true;
		} else {
			System.out.println(serverSentence);
			return false;
		}
		
		
	}
	
	// Receives the file and overwrite if needed
	private void receiveFile(String filename, long fileSize) throws IOException {
		File file = new File(DEFAULT_DIRECTORY.getPath().toString() + "/" + filename);
		FileOutputStream fileOutputStream = new FileOutputStream(file, false);
		BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(fileOutputStream);

		/*
		 *  Read and write for all bytes of files
		 *	Does it until all file is passed
		 */
		for(int i = 0; i < fileSize; i++) {
			bufferedOutputStream.write(dataInServer.read());
		}

		bufferedOutputStream.close();
		fileOutputStream.close();
	}
	

	public static void main(String[] args) throws UnknownHostException, IOException {
		/*
		 * Try to start working with server
		 *	if can't catch exception and print fail message
		 */
		try {
			Client client = new Client();
			client.start();
		}catch(UnknownHostException ue){
			System.out.println("Can't connect to server, shutting down");
		}catch(IOException ie){
			System.out.println("Can't connect to server, shutting down");
		}catch(Exception e) {
			System.out.println("Error working with server");
		}
		
		
	}
}
