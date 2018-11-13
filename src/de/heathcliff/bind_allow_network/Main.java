package de.heathcliff.bind_allow_network;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Properties;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class Main {
	
	// Logger
	private static final Logger logger = Logger.getLogger(Main.class.getName());
	private static FileHandler logfile = null;
	
	// database connection
	private static Connection connection = null;

	public static void main(String[] args) {
		// setup logger
        try {
            logfile = new FileHandler(System.getProperty("user.dir") + "/bindAN.log", true);
        } catch (Exception e) {
        	System.err.println("Could not initialize Logger");
            e.printStackTrace();
        }

        logfile.setFormatter(new SimpleFormatter());
        logger.setLevel(Level.FINE);
        logger.addHandler(logfile);
		
		// read bindAN.properties
		Properties prop = new Properties();
		InputStream inpStream;
		try {
			inpStream = new FileInputStream(System.getProperty("user.dir") + "/bindAN.properties");
			prop.load(inpStream);
		} catch (FileNotFoundException e) {
			logger.severe("Could not find db.properties");
			logger.log(Level.SEVERE, e.getMessage(), e);
			System.exit(1);
		} catch (IOException e) {
			logger.severe("Could not load Properties");
			logger.log(Level.SEVERE, e.getMessage(), e);
			System.exit(1);
		}
		String dbUrl = prop.getProperty("dbUrl");
		String username = prop.getProperty("dbUser");
		String password = prop.getProperty("dbPassword");
		String domain = prop.getProperty("domain");
		String loglevel = prop.getProperty("loglevel");
		
		// set loglevel
		setLogLevel(loglevel);
		
		// create db connection
		try {
			connection = (Connection) DriverManager.getConnection(dbUrl, username, password);
		} catch (SQLException e) {
			logger.severe("Could not connect to database");
			logger.log(Level.SEVERE, e.getMessage(), e);
			System.exit(1);
		}
		
		// get last ip
		String old_ip = "";
		try {
			Statement state = connection.createStatement();
			ResultSet result = state.executeQuery("SELECT IPv4 FROM bindAN_knownIPs WHERE Timestamp in (SELECT MAX(Timestamp) FROM bindAN_knownIPs);");
			if (result.next()) {
				old_ip = result.getString(1);
			}
		} catch (SQLException e) {
			logger.severe("Could not retrieve old ip from db");
			logger.log(Level.SEVERE, e.getMessage(), e);
			closeDBConn(true);
		}
		
		// get new IP
		InetAddress address = null;
		try {
			address = InetAddress.getByName(domain);
		} catch (UnknownHostException e) {
			logger.severe("Could not resolve host");
			logger.log(Level.SEVERE, e.getMessage(), e);
			closeDBConn(true);
		}
		String new_ip = address.getHostAddress();
		
		// check if IP changed
		if (!old_ip.equals(new_ip)) {
			
			// change IP in bind configuration
			ArrayList<String> lines = new ArrayList<String>();
			File bindConf = new File("/etc/bind/named.conf.options");
			try {
				// read config file
				FileReader fr = new FileReader(bindConf);
				BufferedReader br = new BufferedReader(fr);
				String line = null;
				while ((line = br.readLine()) != null) {
					lines.add(line);
					// change IP
					if (line.equals("acl \"heathcliff26\" {")) {
						line = br.readLine();
						line = "\t" + new_ip + ";";
						lines.add(line);
					}
				}
				// write config file
				FileWriter fw = new FileWriter(bindConf);
				BufferedWriter out = new BufferedWriter(fw);
				Iterator<String> it = lines.iterator();
				while (it.hasNext()) {
					out.write(it.next());
					out.newLine();
				}
				br.close();
				out.close();
			} catch (IOException e) {
				logger.severe("Could not find bind configuration");
				logger.log(Level.SEVERE, e.getMessage(), e);
				closeDBConn(true);
			}
			
			// reload configuration
			Process proc = null;
			try {
				proc = Runtime.getRuntime().exec("/etc/init.d/bind9 reload");
				proc.waitFor();
			} catch (IOException e) {
				logger.severe("Could not reload bind");
				logger.log(Level.SEVERE, e.getMessage(), e);
				closeDBConn(true);
			} catch (InterruptedException e) {
				logger.severe("Unexpected Error on reload bind");
				logger.log(Level.SEVERE, e.getMessage(), e);
				if (proc != null) {
					proc.destroy();
				}
				closeDBConn(true);
			} finally {
				if (proc != null) {
					proc.destroy();
				}
			}
			
			// save new IP to db
			try {
				PreparedStatement prepState = connection.prepareStatement("INSERT INTO bindAN_knownIPs (IPv4) VALUES (?);");
				prepState.setString(1, new_ip);
				if (prepState.executeUpdate() == 1) {
					logger.info("IP updated to " + new_ip);
				}
			} catch (SQLException e) {
				logger.severe("Could not insert new ip into db");
				logger.log(Level.SEVERE, e.getMessage(), e);
			} finally {
				closeDBConn(false);
			}
		} else {
			logger.fine("IP is still " + new_ip);
		}
	}
	
	private static void setLogLevel(String loglevel) {
		if (loglevel.equals("severe") || loglevel.equals("error")) {
			logger.setLevel(Level.SEVERE);
		} else if (loglevel.equals("info")) {
			logger.setLevel(Level.INFO);
		} else if (loglevel.equals("fine")) {
			logger.setLevel(Level.FINE);
		} else if (loglevel.equals("off")) {
			logger.setLevel(Level.OFF);
		} else {
			logger.severe("Could not set loglevel");
		}
	}

	private static void closeDBConn(boolean exit) {
		try {
			connection.close();
		} catch (SQLException e) {
			logger.severe("Could not close db connection");
			logger.log(Level.SEVERE, e.getMessage(), e);
		}
		if (exit) {
			System.exit(1);
		}
	}

}
