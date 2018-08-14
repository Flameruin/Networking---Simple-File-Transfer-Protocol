package server;

/*
 * Static DB for users
 * THIS IS NOT A REAL DB ONLY FOR DEBUGING
 * If you want to use this SFTP you need to change database to be dynamic
 */

 interface UserAcctPassDB {
	//Important to keep connected info (user ,account, password) at the same index
	static final String[] user = {"user","admin"};
	static final String[] account = {"acct",""};
	static final String[] password = {"pass",""};
	
	
	static String[] getUsers(){	
	
		return user;
	}
	
	static int getAccountAmount()
	{
		return account.length;
	}
	
	static int getPasswordAmount()
	{
		return password.length;
	}
	
	static String getAccount(int index){	
		return account[index];
	}
	
	static String getPassword(int index){	
		return password[index];
	}
}
