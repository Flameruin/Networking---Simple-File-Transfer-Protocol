package server;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SFTPConnection extends Thread{

	/*
	 * Using regular expression to make a string to get rid of unwanted delimiters from messages
	 * all spaces are used as delimiters (when split remove blank)
	 */
	private static final String delims ="\\s+";//Maybe get rid of these as well? -> /^,;\<>[|:&]+$?
	
	/*
	 * Booleans which are used to verify the users
	 * Used for logIn and logOff purposes 
	 */
	private boolean accountValid = false;
	private boolean passwordOk = false;
	private boolean loggedIn = false;
	/*
	 * A very roundabout way to know when we are in CDIR command
	 * Used so we want send unwanted messages
	 */
	private boolean doingCdirAcctPassCheck = false;
	/*
	 * As stated our database is very naive and should not be used as a reference to a real one
	 * We use the userIndex to know where in the DB the user is so we shall look at his parameters
	 */
	private static int userIndex = -1;
	/*
	 * Pointing to default directory of the server
	 * Can be changed at at programmer will
	 */
	private static final File DEFAULT_DIRECTORY = FileSystems.getDefault().getPath("RootFolder/ServerFolder").toFile().getAbsoluteFile();
	
	//socket to connect
	private Socket connectionSocket;
	
	private BufferedReader inClient;
	private DataOutputStream outClient;
	
	private DataOutputStream dataOutClient;

	/*
	 * When using CDIR command need to change the path
	 * so we need to know be able to change the current working directory
	 */
	private File currentDirectory = DEFAULT_DIRECTORY;
		
	
	SFTPConnection(Socket connectionSocket) throws IOException{
		this.connectionSocket = connectionSocket;
				
		// Messages in/out
		this.inClient = new BufferedReader(new InputStreamReader(connectionSocket.getInputStream()));
		this.outClient = new DataOutputStream(connectionSocket.getOutputStream());
		
		// Data output to send files
			this.dataOutClient = new DataOutputStream(connectionSocket.getOutputStream());
	}
	
	@Override
	public void run() {
		String clientSentence = "";
		/*
		 * When we first connect to the client the server sends his greeting
		 * Used to let the client to know  he's connected
		 * Greeting from out SFTP server
		 */
		System.out.println(String.format("Connected to "+connectionSocket.getRemoteSocketAddress().toString()));
		sendMessage("+ACN-Kinneret SFTP Service");
		
		while(true) {

			/*
			 * Client connection can be lost for many reasons
			 *  Close thread if connection is closed
			 */
			if (connectionSocket.isClosed()) {
				System.out.println("Connection to "+connectionSocket.getRemoteSocketAddress().toString()+" closed");
				
				return;
			}

			clientSentence = readMessage();

			/*
			 * SFTP command is  4 characters so if less we return error
			 * If the length is OK we check the first 4 letters to see if we have a match to a command 
			 * Names of command are logical and a more deta
			 */
			if (clientSentence.length() >= 4) {
				switch(clientSentence.substring(0, 4).toUpperCase()) {
				case "USER":
					userCommand(clientSentence);
					break;
					
				case "ACCT":
					acctCommand(clientSentence);
					break;

				case "PASS":
					passCommand(clientSentence);
					break;

				case "LIST":
					listCommand(clientSentence);
					break;

				case "CDIR":
					cdirCommand(clientSentence);
					break;
					//KILL is delete
				case "KILL":
					killCommand(clientSentence);
					break;
			/*
			 *	Done just send message to get it done and print to disconnection to log 
			 */
				case "DONE":
					System.out.println(String.format("Disconnected from "+connectionSocket.getRemoteSocketAddress().toString()));
					sendMessage("+ACN-Kinneret closing connection");

					return;  // Connection done, stop connection

				case "RETR":
					retrCommand(clientSentence);
					break;

/*
 * Invalid command is invalid
 * send error message to client
 */
				default:
						sendMessage("- Invalid command");
					break;
				}
			} else if (clientSentence.length() < 4) {
				sendMessage("- Invalid command");
			}

		}
	}

/*
 * Reads messages from client
 */
	private String readMessage() {
		String sentence = "";
		int character = 0;
		
		while (true){
			try {
				character = inClient.read(); 
			} catch (IOException e) {
				e.printStackTrace();
			}
			
			// <NULL> detected and 
			if (character == 0) {
				break;
			}
			
			// Builds a full sentence by conc the characters
			sentence = sentence.concat(Character.toString((char)character));
		}

		return sentence;
	}

	/* 
	 * Writes ASCII message string terminated by a <NULL> ('\u0000').
	 * */
	private void sendMessage(String sentence){
		try {
			outClient.writeBytes(sentence.concat(Character.toString('\u0000')));
		} catch (IOException e) {}
	}
	// Current client log in
	private void logOn() {
			
		userIndex = -1;
		accountValid = false;
		passwordOk = false;
		loggedIn = true;
	}
	
	// Current client log off
	private void logOff() {
			
		userIndex = -1;
		accountValid = false;
		passwordOk = false;
		loggedIn = false;
	}

	// UESR command
	/*
	 * check if user exist in DB and return to client
	 * if user is privileged do login
	 */
	private boolean userCommand(String clientSentence) {
		String[] tokens = clientSentence.split(delims,2); //Puts arguments to string without delims
		
		// Only USER command argument
		if (tokens.length==1) {
			sendMessage("-Invalid user-id, try again");
			return false;
		}		
		String userID = tokens[1];
		String[] users = UserAcctPassDB.getUsers();

		//search for user in DB and verify
		for (int i=0;i<users.length;i++) {
			
			// Iterate through the entire userbase to find a match
			if(users[i].equals(userID)) {
				userIndex=i;
				//Don't need an account or password
				if(UserAcctPassDB.getAccount(userIndex).equals("") && 
						UserAcctPassDB.getPassword(userIndex).equals(""))
				{
	        		logOn();
	        		sendMessage(String.format("!"+userID+" logged in"));
				}else {
					sendMessage("+User-id valid, send account and password");
				}
				return true;
			}
				
		}
		//Invalid user-id
		sendMessage(String.format("-Invalid user-id, try again"));
		logOff();
		return false;	
		}

	// ACCT command
	/*
	 * check if account exists if it does ask for password if password already confirmed login
	 * else return error
	 */
	private boolean acctCommand(String clientSentence) {
		
		String[] tokens = clientSentence.split(delims,2); //Puts arguments to string without delims
		
		// Only ACCT command argument so send negative message
		if (tokens.length==1) {
			if(!doingCdirAcctPassCheck)
			{	sendMessage("-Invalid account, try again");
				logOff();
			}
			return false;
		}	

		String acct = tokens[1];
		
		
		if (passwordOk) {
			
			// Password has been previously sent and checked
			if (UserAcctPassDB.getAccount(userIndex).equals(acct)) {
				logOn();
				if(!doingCdirAcctPassCheck)
				sendMessage("! Account valid, logged-in");			
				return true;
			}

		} 
		//search for user in DB and verify
		for(int i=0;i<UserAcctPassDB.getAccountAmount();i++)
		{
			if (UserAcctPassDB.getAccount(i).equals(acct)) 
			{
				userIndex = i;
				sendMessage("+Account valid, send password");
				accountValid = true;
				passwordOk = false;
				return true;
			}
		}
		if(!doingCdirAcctPassCheck)
		{	
			sendMessage("-Invalid account, try again");			
			logOff();
		}
		return false;
			
	}
	
	// PASS command
	/*
	 * check if password exists if it does ask for account if account already confirmed login
	 * else return error
	 */
	private boolean passCommand(String clientSentence) {
		
		String[] tokens = clientSentence.split(delims,2); //Puts arguments to string without delims
		
		// Only PASS command argument
		if (tokens.length==1) {
			if(!doingCdirAcctPassCheck) {
				sendMessage("-Wrong password, try again");
				logOff();
			}
				return false;
		}	

		String pass = tokens[1];
		
		if (accountValid) {	
			// Account has been previously sent and checked
			if (UserAcctPassDB.getPassword(userIndex).equals(pass)) {
				logOn();
				if(!doingCdirAcctPassCheck)
					sendMessage("! Logged in");			
				return true;
			}
		} 
		//search for user in DB and verify	
		for(int i=0;i<UserAcctPassDB.getPasswordAmount();i++)
		{
			if (UserAcctPassDB.getPassword(i).equals(pass)) 
			{
				userIndex = i;
				sendMessage("+Send account");
				passwordOk = true;
				accountValid = false;
				return true;
			}
		}	
		
		if(!doingCdirAcctPassCheck)
		{
			sendMessage("-Wrong password, try again");		
			logOff();
		}
	
		return false;
	}
	
	// LIST command /list the files
	private boolean listCommand(String clientSentence) {
		
		if (!loggedIn) {
			sendMessage("-Not logged in");
			
			return false;
		}
		///////////////////////////////////////////
		
		String mode = "";
		File path = currentDirectory;
		
		//Get current directory .. or just manual insert ServerFolder
		String currentPath[] = path.toString().split("\\W");
		
		
		
		String[] tokens = clientSentence.split(delims,3); //Puts arguments to string without delims
		
		// Only LIST command argument
		if (tokens.length==1) {
			sendMessage("-Missing listing argument");
			logOff();
			return false;
		}	

		/*
		 * check the wanted list mode
		 * better to upper once than equalsIgnoreCase multiple times
		 */
		mode = tokens[1].toUpperCase();
		
		// Invalid mode
		if (!mode.equals("F") && !mode.equals("V")) {
	
			sendMessage("-Missing listing argument");
			logOff();
			return false;
		}
		
		try {
			
			path = new File(currentDirectory.toString() + "/" + tokens[2]);
			
			// Requested directory doesn't exist
			if (!path.isDirectory()) {
				sendMessage(String.format("-Not a directory"));
				logOff();
				return false;
			}
			currentPath[currentPath.length-1]="+" + tokens[2] +"\n";
		} catch (ArrayIndexOutOfBoundsException e) {
			currentPath[currentPath.length-1] = "+" + currentPath[currentPath.length-1] +"\n"; 
		}
		  
		/*
		 * We want to know the date in a format we like no matter which system we are using so setting date format
		 * Date format for verbose in USA format - d-FULL MONTH-2charYear-0-23hours-minutes (2-July-14 00:53)
		 */
		SimpleDateFormat dateFormat = new SimpleDateFormat("d-MMMM-yy HH:mm",Locale.US);
	
		
		//Start output with current path
		String output = currentPath[currentPath.length-1];  
		//Get the list we are going to print of files and directories
		File files[] = path.listFiles();
		
		/*
		 *  Go through each file/directory in the directory
		 *  and prints it's wanted info
		 *  If F just print names
		 *  If V prints name+size of file+owner+last write date+ can read/write/execute
		 */
		for (File f : files) {
			String filename = f.getName();
			
			// Append / to directories
			if (f.isDirectory()) {
				filename = filename.concat("/");
			}
			
			// Verbose, get information on the file
			if (mode.equals("V")) {
				long modifiedTime = f.lastModified();
				String lastWriteDate = dateFormat.format(new Date(modifiedTime));
				String size = String.valueOf(f.length());
				String owner = "";
				
				String readWriteExecute="";
				if(f.canRead())readWriteExecute=readWriteExecute.concat("r");
				if(f.canWrite())readWriteExecute=readWriteExecute.concat("w");
				if(f.canExecute())readWriteExecute=readWriteExecute.concat("e");	
				
				try {
					 FileOwnerAttributeView attr = Files.getFileAttributeView(f.toPath(), FileOwnerAttributeView.class);
					 owner = attr.getOwner().getName();
				} catch (IOException e) {	
					e.printStackTrace();
				}

				//  file name, size, protection, last write date,    owner
				//using %s to get the list to print in a nice looking way by setting it's place
				output = output.concat(String.format("%-25s %-15s %-5s %10s %20s \r\n", filename, size, readWriteExecute, lastWriteDate, owner));
			
			// Non verbose, filename only
			} else {
				output = output.concat(String.format(filename+" \r\n"));
			}
		}
		
		sendMessage(output);
		
		return true;
	}
	
	// CDIR command/change path
	/*
	 * used to change working path of server
	 * can be used without login but login is needed once used
	 * because of that must allow login from within the command 
	 * to so we used the already built commands of PASS, ACCT
	 * to know it's connected to CDIR used a boolean to change the sent sentences to client
	 */
	private boolean cdirCommand(String clientSentence) {
		String newDirName = "";
		
		String[] tokens = clientSentence.split(delims,2); //Puts arguments to string without delims
		
		// Only CDIR command argument
		if (tokens.length==1) {
			sendMessage("-Can't connect to directory because: Missing argument");
			return false;
		}	

		//better to upper once than equalsIgnoreCase multiple times
		newDirName = tokens[1];
		
		// ~ points to the DEAFULT_DIRECTORY // ours is ServerFolder
		if (newDirName.charAt(0) == '~') {
			newDirName = newDirName.replaceAll("~", "/");

			currentDirectory = DEFAULT_DIRECTORY;
		}
		
		// Add / for directory
		if (newDirName.charAt(0) != '/') {
			newDirName = String.format("/%s", newDirName);
		}
		
		//Add / to directory path end (path ends with \ )
		if (newDirName.charAt(newDirName.length()-1) != '/') {
			newDirName = newDirName.concat("/");
		}
		
		File newDir = new File(currentDirectory.toString().concat(newDirName)).toPath().normalize().toFile();
		
		// Client trying access folder above allocated "root" folder.
		if (newDir.compareTo(DEFAULT_DIRECTORY.getAbsoluteFile()) < 0){
			sendMessage("-Can't connect to directory because: outside working range");
			logOff();
			return false;
		}
		
		// Specified directory is not a directory
		if (!newDir.isDirectory()) {
			sendMessage("-Can't connect to directory because: no such directory");
			logOff();
			return false;
		}
		
		// Replace portion of the path to ~
		// Client doesn't need to know the absolute directory on the server
		String newDirReply = ("~"+ newDir.toString().substring(DEFAULT_DIRECTORY.toString().length()));
		if(newDirReply.length()==1)
			newDirReply ="(ServerFolder) ~";
		// Already logged in
		if (loggedIn) {
			currentDirectory = newDir;
			sendMessage(String.format("!Changed working dir to %s", newDirReply));
			
			return true;
			
		// Need to log in
		} else {
			sendMessage(String.format("+directory ok, send account/password", newDir));
			
			doingCdirAcctPassCheck=true;
			// Run CDIR authentication procedure
			if (cdirAcctPassCheck()) {
				currentDirectory = newDir;
				sendMessage("!Changed working dir to " + newDirReply);
				
				return true;
			}
			
			doingCdirAcctPassCheck=false;
		}
		
		return false;
	}
	
	// KILL command
	/*
	 * gets the filename and tries to delete from the working directory
	 * send messages according to end result
	 */
	private boolean killCommand(String clientSentence) {
		
		if (!loggedIn) {
			sendMessage("-Not logged in");
			logOff();
			return false;
		}
		
		String[] tokens = clientSentence.split(delims,2); //Puts arguments to string without delims
		
		// Only KILL command argument
		if (tokens.length==1) {
			sendMessage("-Not deleted because: no such file");
			logOff();
			return false;
		}	

		
		String filename = tokens[1];
		Path path = new File(currentDirectory.toString().concat("/").concat(filename)).toPath();
		
		// Delete the file
		try {
			Files.delete(path);
			sendMessage(String.format("+%s deleted", filename));
			
			return true;
			
		} catch (NoSuchFileException x) {
		    sendMessage("-Not deleted because: no such file");
		    logOff();
		} catch (IOException x) {
		    sendMessage("-Not deleted because: protected file");
		    logOff();
		}
		
		return false;
	}
	
	// RETR command
	/*
	 * gets the file if exists
	 * send the file size so client will know if it can handle the file
	 * once get a response sentence from client acts accordinly
	 */
	private boolean retrCommand(String clientSentence) {
		if (!loggedIn) {
			sendMessage("-Not logged in");
			logOff();
			return false;
		}
		
		String[] tokens = clientSentence.split(delims,2); //Puts arguments to string without delims
		
		// Only RETR command argument
		if (tokens.length==1) {
			sendMessage("-File doesn't exist");
			logOff();
			return false;
		}	

		String filename = tokens[1];
		
		// Specified file
		File file = new File(currentDirectory.toString() + "/" + filename);
		// Specified file is not a file
		if (!file.isFile()) {
			sendMessage("-File doesn't exist");
			logOff();
			return false;
		}
		
		
		// Get file size
		long fileSize = file.length();
		sendMessage(String.valueOf(fileSize));

		String clientReply = readMessage().toUpperCase();

		// Client doesn't want to store the file
		if (clientReply.equals("STOP")) {
			sendMessage("+ok, RETR aborted");
			
			return false;
		}
	//	sendMessage("The file " + filename + " is sent\r\n" + 
	//			"Should arrive shortly");
		sendFile(file);
		System.out.println(clientReply);
		return true;
	}
	
	
	/*
	 * when CDIR is used and the client is not logged in tries to login
	 * returns to CDIR if it was a success or failure
	 * because the protocol wants very specific response it made some clutter in our code
	 * used boolean to prevent message sending not from the correct place
	 * Change directory // if client not logged in need to login
	 */
	private boolean cdirAcctPassCheck() {
		final String cdirFailLogin="\n-Can't connect to directory because: can't login";
		while(true) {
			if (loggedIn) {				
				return true;
			}
			String clientSentence = readMessage();
			
			if (clientSentence.length() >= 4) {
				switch(clientSentence.substring(0, 4).toUpperCase()) {
				case "ACCT":
					if (!acctCommand(clientSentence)) {
						sendMessage("-Invalid account, try again"+cdirFailLogin);
						logOff();
						return false;
					}
							
					break;
	
				case "PASS":
					if (!passCommand(clientSentence)) {
						sendMessage("-Wrong password, try again"+cdirFailLogin);
						logOff();
						return false;
					}
										
					break;
	
				default:
					sendMessage(cdirFailLogin);
					return false;
				}
				
			} else {
				sendMessage(cdirFailLogin);
				return false;
			}
		}
	}
	
	// Sends requested file to the client
	private boolean sendFile(File file) {
		byte[] bytes = new byte[(int) file.length()];

		try {
			FileInputStream fileInputStream = new FileInputStream(file);
			BufferedInputStream bufferedInputStream = new BufferedInputStream(new FileInputStream(file));
		
			int content = 0;
			
			// Read and send file until the whole file has been sent
			while ((content = bufferedInputStream.read(bytes)) >= 0) {
				dataOutClient.write(bytes, 0, content);

				}
			
			bufferedInputStream.close();
			fileInputStream.close();
			dataOutClient.flush();
	
		} catch (FileNotFoundException e) {
			return false;
		} catch (IOException e) {
			return false;
		}
		return true;
	}
	
}
